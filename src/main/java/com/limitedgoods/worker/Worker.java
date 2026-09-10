package com.limitedgoods.worker;
import com.limitedgoods.payments.*;
import com.limitedgoods.reservations.ReservationService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
@Configuration @EnableScheduling
@ConditionalOnProperty(name="app.worker.enabled",havingValue="true")
public class Worker {
    private static final Logger log=LoggerFactory.getLogger(Worker.class);
    private final ExecutorService executor;
    private final Semaphore slots;
    private final int concurrency;
    private final MeterRegistry metrics;
    private volatile boolean stopping;
    private final PaymentJobs jobs;
    private final PaymentService payments;
    private final PaymentProvider provider;
    private final ReservationService reservations;
    public Worker(PaymentJobs jobs,PaymentService payments,PaymentProvider provider,ReservationService reservations,
                  @Value("${app.worker.concurrency:4}") int concurrency,MeterRegistry metrics) {
        if(concurrency<1) throw new IllegalArgumentException("Worker concurrency must be positive");
        this.jobs=jobs; this.payments=payments; this.provider=provider; this.reservations=reservations;
        this.concurrency=concurrency; this.metrics=metrics;
        this.executor=Executors.newFixedThreadPool(concurrency); this.slots=new Semaphore(concurrency);
        metrics.gauge("goods.worker.active",this,w->w.concurrency-w.slots.availablePermits());
    }
    // Empty queues wait for this timer. A busy slot immediately pulls its next job.
    @Scheduled(fixedDelayString="${app.worker.idle-delay-ms:250}")
    public synchronized void dispatch() {
        for(int i=0;!stopping && i<concurrency && slots.tryAcquire();i++) {
            try { executor.execute(this::drain); }
            catch(RejectedExecutionException e) { slots.release(); if(!stopping) throw e; }
        }
    }
    private void drain() {
        try {
            while(!stopping && !Thread.currentThread().isInterrupted()) {
                var id=jobs.claim();
                if(id==null) return;
                var sample=Timer.start(metrics);
                try {
                    var work=payments.work(id);
                    if(work!=null) {
                        var result=metrics.timer("goods.worker.pg").record(()->provider.resolve(work));
                        payments.apply(id,work.amount(),result);
                        metrics.counter("goods.worker.completed").increment();
                    }
                } finally { sample.stop(metrics.timer("goods.worker.job")); }
            }
        } catch(RuntimeException e) {
            // Stop this drain on infrastructure failure; idle polling and the durable lease recover it.
            metrics.counter("goods.worker.errors").increment();
            log.error("Worker drain failed; lease will recover",e);
        } finally { slots.release(); }
    }
    @Scheduled(fixedDelay=1000)
    public void expire() {
        for(var id:reservations.due()) reservations.expire(id);
    }
    @PreDestroy public synchronized void shutdown() { stopping=true; executor.shutdownNow(); }
}
