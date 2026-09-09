package com.limitedgoods.purchases;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.limitedgoods.admission.AdmissionGate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PurchaseDiagnosticsTest {
    private final Logger logger=(Logger)LoggerFactory.getLogger(PurchaseTrace.class);
    private final ListAppender<ILoggingEvent> events=new ListAppender<>();
    private Level previous;
    private final AdmissionGate gate=mock(AdmissionGate.class);
    private final PurchaseTransactionService transactions=mock(PurchaseTransactionService.class);
    private final PurchaseService service=new PurchaseService(gate,transactions);
    private final PurchaseRequest request=new PurchaseRequest(UUID.randomUUID(),
        List.of(new PurchaseRequest.Item(UUID.randomUUID(),1)));
    @BeforeEach void capture() {
        previous=logger.getLevel(); logger.setLevel(Level.INFO);
        events.start(); logger.addAppender(events);
    }
    @AfterEach void restore() { logger.detachAppender(events); events.stop(); logger.setLevel(previous); }
    @Test void recordsFailureBeforeTransactionBodyAndPreservesException() {
        var failure=new IllegalStateException("connection acquisition failed");
        when(transactions.purchase(anyString(),anyString(),any(),any())).thenThrow(failure);
        assertThatThrownBy(()->service.purchase("user","key",request)).isSameAs(failure);
        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getFormattedMessage()).contains("final_phase=transaction_entry",
            "outcome=IllegalStateException","after_lock_until_proxy_exit_ms=null");
        verify(gate).leave(null);
    }
    @Test void emitsAfterProxyReturnsAndIncludesCompletionPhase() {
        var view=new OrderView(UUID.randomUUID(),"PAYMENT_PENDING",100,Instant.now(),List.of(),List.of());
        when(transactions.purchase(anyString(),anyString(),any(),any())).thenAnswer(call->{
            PurchaseTrace trace=call.getArgument(3);
            trace.next("inventory_lock_query"); trace.inventoryLocked();
            trace.next("response_queries_and_auto_flush"); trace.next("transaction_completion");
            assertThat(events.list).isEmpty();
            return view;
        });
        assertThat(service.purchase("user","key",request)).isSameAs(view);
        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getFormattedMessage()).contains("outcome=success",
            "final_phase=transaction_completion","inventory_lock_query=","transaction_completion=")
            .doesNotContain("after_lock_until_proxy_exit_ms=null");
        verify(gate).leave(null);
    }
    @Test void failureKeepsUnfinishedStageAndDoesNotClaimCommitSuccess() {
        var failure=new IllegalArgumentException("query failure");
        when(transactions.purchase(anyString(),anyString(),any(),any())).thenAnswer(call->{
            PurchaseTrace trace=call.getArgument(3);
            trace.next("inventory_lock_query"); trace.inventoryLocked(); trace.next("user_limit_query");
            throw failure;
        });
        assertThatThrownBy(()->service.purchase("user","key",request)).isSameAs(failure);
        assertThat(events.list.getFirst().getFormattedMessage()).contains("final_phase=user_limit_query",
            "outcome=IllegalArgumentException").doesNotContain("transaction_completion=");
        verify(gate).leave(null);
    }
}
