package com.limitedgoodsreservation.purchase.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.limitedgoodsreservation.product.repository.ProductRepository;
import com.limitedgoodsreservation.purchase.failure.InjectedPurchaseFailureException;
import com.limitedgoodsreservation.purchase.failure.PurchaseFailureInjector;
import com.limitedgoodsreservation.purchase.metrics.PurchaseMetrics;
import com.limitedgoodsreservation.reservation.exception.ReservationFailedRetryableException;
import com.limitedgoodsreservation.reservation.gate.RedisFrontGateDecision;
import com.limitedgoodsreservation.reservation.gate.RedisReservationFrontGate;
import com.limitedgoodsreservation.reservation.metrics.ReservationMetrics;
import com.limitedgoodsreservation.reservation.repository.ReservationRepository;
import com.limitedgoodsreservation.waitingroom.service.WaitingRoomService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class PurchaseServiceCompensationTest {

    @Test
    void compensatesRedisFrontGateAndRestoresActiveTokenWhenReservationPersistenceFails() {
        TestFixture fixture = new TestFixture();
        when(fixture.redisReservationFrontGate.enter(1001L, 1L, "request-1"))
                .thenReturn(RedisFrontGateDecision.ACCEPTED);
        when(fixture.redisReservationFrontGate.compensate(1001L, 1L, "request-1"))
                .thenReturn(true);
        doThrow(new InjectedPurchaseFailureException("boom"))
                .when(fixture.purchaseFailureInjector)
                .maybeFailAfterStockDecisionBeforeReservationSave("run-1", RedisFrontGatePurchaseFlow.ARCHITECTURE_NAME);

        assertThatThrownBy(() -> fixture.flow.purchase(1001L, 1L, "run-1", "request-1"))
                .isInstanceOf(ReservationFailedRetryableException.class);

        verify(fixture.redisReservationFrontGate).compensate(1001L, 1L, "request-1");
        verify(fixture.waitingRoomService).restoreActiveToken(1001L, 1L);
    }

    @Test
    void doesNotRestoreActiveTokenWhenRedisFrontGateCompensationFails() {
        TestFixture fixture = new TestFixture();
        when(fixture.redisReservationFrontGate.enter(1001L, 1L, "request-1"))
                .thenReturn(RedisFrontGateDecision.ACCEPTED);
        when(fixture.redisReservationFrontGate.compensate(1001L, 1L, "request-1"))
                .thenReturn(false);
        doThrow(new InjectedPurchaseFailureException("boom"))
                .when(fixture.purchaseFailureInjector)
                .maybeFailAfterStockDecisionBeforeReservationSave("run-1", RedisFrontGatePurchaseFlow.ARCHITECTURE_NAME);

        assertThatThrownBy(() -> fixture.flow.purchase(1001L, 1L, "run-1", "request-1"))
                .isInstanceOf(ReservationFailedRetryableException.class);

        verify(fixture.redisReservationFrontGate).compensate(1001L, 1L, "request-1");
        verify(fixture.waitingRoomService, never()).restoreActiveToken(1001L, 1L);
    }

    private static class TestFixture {

        private final RedisReservationFrontGate redisReservationFrontGate = mock(RedisReservationFrontGate.class);
        private final ProductRepository productRepository = mock(ProductRepository.class);
        private final ReservationRepository reservationRepository = mock(ReservationRepository.class);
        private final PurchaseFailureInjector purchaseFailureInjector = mock(PurchaseFailureInjector.class);
        private final WaitingRoomService waitingRoomService = mock(WaitingRoomService.class);
        private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        private final RedisFrontGatePurchaseFlow flow;

        private TestFixture() {
            TransactionStatus transactionStatus = mock(TransactionStatus.class);
            when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            flow = new RedisFrontGatePurchaseFlow(
                    redisReservationFrontGate,
                    productRepository,
                    reservationRepository,
                    new PurchaseMetrics(meterRegistry),
                    new ReservationMetrics(meterRegistry),
                    purchaseFailureInjector,
                    waitingRoomService,
                    transactionManager
            );
        }
    }
}
