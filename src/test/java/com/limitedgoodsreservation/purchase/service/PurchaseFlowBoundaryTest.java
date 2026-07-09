package com.limitedgoodsreservation.purchase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.limitedgoodsreservation.product.repository.ProductRepository;
import com.limitedgoodsreservation.purchase.dto.PurchaseResult;
import com.limitedgoodsreservation.purchase.failure.PurchaseFailureInjector;
import com.limitedgoodsreservation.purchase.metrics.PurchaseMetrics;
import com.limitedgoodsreservation.reservation.entity.Reservation;
import com.limitedgoodsreservation.reservation.entity.ReservationStatus;
import com.limitedgoodsreservation.reservation.gate.RedisFrontGateDecision;
import com.limitedgoodsreservation.reservation.gate.RedisReservationFrontGate;
import com.limitedgoodsreservation.reservation.metrics.ReservationMetrics;
import com.limitedgoodsreservation.reservation.repository.ReservationRepository;
import com.limitedgoodsreservation.stock.strategy.RdbAtomicStockStrategy;
import com.limitedgoodsreservation.waitingroom.service.ActiveTokenRequiredException;
import com.limitedgoodsreservation.waitingroom.service.WaitingRoomService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class PurchaseFlowBoundaryTest {

    @Test
    void rdbAtomicRejectsMissingActiveTokenBeforeDbReservationWork() {
        RdbFixture fixture = new RdbFixture();
        doThrow(new ActiveTokenRequiredException(1L, 1001L))
                .when(fixture.waitingRoomService).consumeActiveTokenOrThrow(1001L, 1L);

        assertThatThrownBy(() -> fixture.flow.purchase(1001L, 1L, "run-1", "request-1"))
                .isInstanceOf(ActiveTokenRequiredException.class);

        verify(fixture.transactionManager, never()).getTransaction(any());
        verify(fixture.reservationRepository, never()).findByIdempotencyKey("request-1");
        verify(fixture.rdbAtomicStockStrategy, never()).deduct(1L);
    }

    @Test
    void redisFrontGateRejectsMissingActiveTokenBeforeDbReservationWork() {
        RedisFixture fixture = new RedisFixture();
        when(fixture.redisReservationFrontGate.enter(1001L, 1L, "request-1"))
                .thenReturn(RedisFrontGateDecision.ACTIVE_TOKEN_REQUIRED);

        assertThatThrownBy(() -> fixture.flow.purchase(1001L, 1L, "run-1", "request-1"))
                .isInstanceOf(ActiveTokenRequiredException.class);

        verify(fixture.transactionManager, never()).getTransaction(any());
        verify(fixture.reservationRepository, never()).findByIdempotencyKey("request-1");
        verify(fixture.reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void redisFrontGateReusesReservationWhenReservedMarkerExists() {
        RedisFixture fixture = new RedisFixture();
        Reservation reservation = mock(Reservation.class);
        when(reservation.matches(1001L, 1L)).thenReturn(true);
        when(reservation.getId()).thenReturn(10L);
        when(reservation.getUserId()).thenReturn(1001L);
        when(reservation.getProductId()).thenReturn(1L);
        when(reservation.getStatus()).thenReturn(ReservationStatus.RESERVED);
        when(fixture.redisReservationFrontGate.enter(1001L, 1L, "request-1"))
                .thenReturn(RedisFrontGateDecision.IDEMPOTENCY_RESERVED);
        when(fixture.reservationRepository.findByIdempotencyKey("request-1"))
                .thenReturn(Optional.of(reservation));

        PurchaseResult result = fixture.flow.purchase(1001L, 1L, "run-1", "request-1");

        assertThat(result.created()).isFalse();
        assertThat(result.response().reservationId()).isEqualTo(10L);
        verify(fixture.transactionManager, never()).getTransaction(any());
    }

    private static class RdbFixture {

        private final RdbAtomicStockStrategy rdbAtomicStockStrategy = mock(RdbAtomicStockStrategy.class);
        private final ProductRepository productRepository = mock(ProductRepository.class);
        private final ReservationRepository reservationRepository = mock(ReservationRepository.class);
        private final PurchaseFailureInjector purchaseFailureInjector = mock(PurchaseFailureInjector.class);
        private final WaitingRoomService waitingRoomService = mock(WaitingRoomService.class);
        private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        private final RdbAtomicPurchaseFlow flow;

        private RdbFixture() {
            TransactionStatus transactionStatus = mock(TransactionStatus.class);
            when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            flow = new RdbAtomicPurchaseFlow(
                    rdbAtomicStockStrategy,
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

    private static class RedisFixture {

        private final RedisReservationFrontGate redisReservationFrontGate = mock(RedisReservationFrontGate.class);
        private final ProductRepository productRepository = mock(ProductRepository.class);
        private final ReservationRepository reservationRepository = mock(ReservationRepository.class);
        private final PurchaseFailureInjector purchaseFailureInjector = mock(PurchaseFailureInjector.class);
        private final WaitingRoomService waitingRoomService = mock(WaitingRoomService.class);
        private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        private final RedisFrontGatePurchaseFlow flow;

        private RedisFixture() {
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
