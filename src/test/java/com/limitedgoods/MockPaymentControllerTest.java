package com.limitedgoods;
import com.limitedgoods.mockpg.*;
import com.limitedgoods.payments.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MockPaymentControllerTest {
    @Test void delayRangeIsValidatedWithoutTimingAssertions() {
        var store=mock(MockPaymentStore.class);
        assertThatThrownBy(()->new MockPaymentController(store,"secret",-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new MockPaymentController(store,"secret",5001)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(()->new MockPaymentController(store,"secret",5000)).doesNotThrowAnyException();
    }
    @Test void lostResponseStillFollowsDurableStore() {
        var store=mock(MockPaymentStore.class);
        var work=new PaymentService.Work(UUID.randomUUID(),UUID.randomUUID(),100,PaymentService.Scenario.LOST_RESPONSE,Instant.now().plusSeconds(70));
        when(store.accept(work)).thenReturn(new MockPaymentStore.Receipt(null,true));
        assertThat(new MockPaymentController(store,"secret",0).accept("secret",work).getStatusCode().value()).isEqualTo(504);
        verify(store).accept(work);
    }
    @Test void interruptedResponseDelayPreservesInterruptAfterStore() {
        var store=mock(MockPaymentStore.class);
        var work=new PaymentService.Work(UUID.randomUUID(),UUID.randomUUID(),100,PaymentService.Scenario.SUCCESS,Instant.now().plusSeconds(70));
        when(store.accept(work)).thenAnswer(invocation->{
            Thread.currentThread().interrupt(); // No real sleep; simulate cancellation after durable receipt.
            return new MockPaymentStore.Receipt(null,false);
        });
        try {
            assertThat(new MockPaymentController(store,"secret",200).accept("secret",work).getStatusCode().value()).isEqualTo(503);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(store).accept(work);
        } finally { Thread.interrupted(); }
    }
}
