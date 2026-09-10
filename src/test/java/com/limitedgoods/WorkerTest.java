package com.limitedgoods;
import com.limitedgoods.worker.*;
import com.limitedgoods.payments.*;
import com.limitedgoods.reservations.ReservationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerTest {
    @Test void oneDispatchDrainsMoreThanConcurrencyWithoutAnotherSchedulerTick() throws Exception {
        var jobs=mock(PaymentJobs.class); var payments=mock(PaymentService.class);
        var provider=mock(PaymentProvider.class); var done=new CountDownLatch(12);
        var remaining=new AtomicInteger(12);
        when(jobs.claim()).thenAnswer(c->remaining.getAndDecrement()>0?UUID.randomUUID():null);
        when(payments.work(any())).thenAnswer(c->new PaymentService.Work(c.getArgument(0),UUID.randomUUID(),100,
            PaymentService.Scenario.SUCCESS,Instant.now().plusSeconds(300)));
        when(provider.resolve(any())).thenReturn(PaymentService.Result.SUCCEEDED);
        doAnswer(c->{done.countDown(); return null;}).when(payments).apply(any(),eq(100L),eq(PaymentService.Result.SUCCEEDED));
        var metrics=new SimpleMeterRegistry();
        var worker=new Worker(jobs,payments,provider,mock(ReservationService.class),2,metrics);
        try {
            worker.dispatch();
            assertThat(done.await(5,TimeUnit.SECONDS)).isTrue();
            verify(provider,times(12)).resolve(any());
        } finally { worker.shutdown(); }
    }
    @Test void blockedProviderDoesNotCreateAnUnboundedExecutorQueue() throws Exception {
        var jobs=mock(PaymentJobs.class); var payments=mock(PaymentService.class);
        var provider=mock(PaymentProvider.class); var entered=new CountDownLatch(2); var release=new CountDownLatch(1);
        var claimed=new AtomicInteger();
        when(jobs.claim()).thenAnswer(c->{claimed.incrementAndGet(); return UUID.randomUUID();});
        when(payments.work(any())).thenAnswer(c->new PaymentService.Work(c.getArgument(0),UUID.randomUUID(),100,
            PaymentService.Scenario.SUCCESS,Instant.now().plusSeconds(300)));
        when(provider.resolve(any())).thenAnswer(c->{entered.countDown(); release.await(5,TimeUnit.SECONDS); return PaymentService.Result.SUCCEEDED;});
        var worker=new Worker(jobs,payments,provider,mock(ReservationService.class),2,new SimpleMeterRegistry());
        try {
            worker.dispatch(); assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            for(int i=0;i<20;i++) worker.dispatch();
            assertThat(claimed.get()).isEqualTo(2);
        } finally { worker.shutdown(); release.countDown(); }
    }
}
