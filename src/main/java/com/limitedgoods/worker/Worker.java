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
@Configuration @EnableScheduling
@ConditionalOnProperty(name="app.worker.enabled",havingValue="true")
public class Worker {
    private static final Logger log=LoggerFactory.getLogger(Worker.class);
    private final ExecutorService executor=Executors.newFixedThreadPool(4);
    private final Semaphore slots=new Semaphore(4);
    private final PaymentJobs jobs;
    private final PaymentService payments;
    private final PaymentProvider provider;
    private final ReservationService reservations;
    public Worker(PaymentJobs jobs,PaymentService payments,PaymentProvider provider,ReservationService reservations) {
        this.jobs=jobs; this.payments=payments; this.provider=provider; this.reservations=reservations;
    }
    @Scheduled(fixedDelayString="${app.worker.delay-ms}")
    public void dispatch() {
        for(int i=0;i<4 && slots.tryAcquire();i++) {
            java.util.UUID id;
            try { id=jobs.claim(); }
            catch(RuntimeException e) { slots.release(); throw e; }
            if(id==null) { slots.release(); break; }
            executor.submit(()->{
                try {
                    var work=payments.work(id);
                    if(work!=null) payments.apply(id,work.amount(),provider.resolve(work));
                } catch(RuntimeException e) {
                    // Durable lease expires even when the process dies before this log.
                    log.error("payment_attempt={} processing failed; lease will recover",id,e);
                } finally { slots.release(); }
            });
        }
    }
    @Scheduled(fixedDelay=1000)
    public void expire() {
        for(var id:reservations.due()) reservations.expire(id);
    }
    @PreDestroy public void shutdown() { executor.shutdownNow(); }
}
