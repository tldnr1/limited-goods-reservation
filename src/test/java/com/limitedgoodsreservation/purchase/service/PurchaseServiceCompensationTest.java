package com.limitedgoodsreservation.purchase.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.limitedgoodsreservation.product.entity.Product;
import com.limitedgoodsreservation.product.repository.ProductRepository;
import com.limitedgoodsreservation.purchase.failure.InjectedPurchaseFailureException;
import com.limitedgoodsreservation.purchase.failure.PurchaseFailureInjector;
import com.limitedgoodsreservation.purchase.metrics.PurchaseMetrics;
import com.limitedgoodsreservation.reservation.exception.ReservationFailedRetryableException;
import com.limitedgoodsreservation.reservation.metrics.ReservationMetrics;
import com.limitedgoodsreservation.reservation.repository.ReservationRepository;
import com.limitedgoodsreservation.stock.strategy.RedisLuaStockStrategy;
import com.limitedgoodsreservation.stock.strategy.StockCompensationService;
import com.limitedgoodsreservation.stock.strategy.StockDeductionResult;
import com.limitedgoodsreservation.stock.strategy.StockDeductionStrategy;
import com.limitedgoodsreservation.stock.strategy.StockDeductionStrategyResolver;
import com.limitedgoodsreservation.waitingroom.service.WaitingRoomService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PurchaseServiceCompensationTest {

    @Test
    void compensatesRedisStockAndRestoresActiveTokenWhenReservationPersistenceFails() {
        TestFixture fixture = new TestFixture();
        when(fixture.stockCompensationService.compensate(RedisLuaStockStrategy.STRATEGY_NAME, 1L))
                .thenReturn(true);
        doThrow(new InjectedPurchaseFailureException("boom"))
                .when(fixture.purchaseFailureInjector)
                .maybeFailAfterStockDecisionBeforeReservationSave("run-1", RedisLuaStockStrategy.STRATEGY_NAME);

        assertThatThrownBy(() -> fixture.service.purchase(1001L, 1L, "run-1", "request-1"))
                .isInstanceOf(ReservationFailedRetryableException.class);

        verify(fixture.stockCompensationService).compensate(RedisLuaStockStrategy.STRATEGY_NAME, 1L);
        verify(fixture.waitingRoomService).restoreActiveToken(1001L, 1L);
    }

    @Test
    void doesNotRestoreActiveTokenWhenRedisCompensationFails() {
        TestFixture fixture = new TestFixture();
        when(fixture.stockCompensationService.compensate(RedisLuaStockStrategy.STRATEGY_NAME, 1L))
                .thenReturn(false);
        doThrow(new InjectedPurchaseFailureException("boom"))
                .when(fixture.purchaseFailureInjector)
                .maybeFailAfterStockDecisionBeforeReservationSave("run-1", RedisLuaStockStrategy.STRATEGY_NAME);

        assertThatThrownBy(() -> fixture.service.purchase(1001L, 1L, "run-1", "request-1"))
                .isInstanceOf(ReservationFailedRetryableException.class);

        verify(fixture.stockCompensationService).compensate(RedisLuaStockStrategy.STRATEGY_NAME, 1L);
        verify(fixture.waitingRoomService, never()).restoreActiveToken(1001L, 1L);
    }

    private static class TestFixture {

        private final StockDeductionStrategyResolver stockDeductionStrategyResolver =
                mock(StockDeductionStrategyResolver.class);
        private final StockDeductionStrategy stockDeductionStrategy = mock(StockDeductionStrategy.class);
        private final ProductRepository productRepository = mock(ProductRepository.class);
        private final ReservationRepository reservationRepository = mock(ReservationRepository.class);
        private final PurchaseFailureInjector purchaseFailureInjector = mock(PurchaseFailureInjector.class);
        private final StockCompensationService stockCompensationService = mock(StockCompensationService.class);
        private final WaitingRoomService waitingRoomService = mock(WaitingRoomService.class);
        private final PurchaseService service;

        private TestFixture() {
            Product product = mock(Product.class);
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            when(product.getId()).thenReturn(1L);
            when(stockDeductionStrategyResolver.selectedStrategy()).thenReturn(stockDeductionStrategy);
            when(stockDeductionStrategy.strategyName()).thenReturn(RedisLuaStockStrategy.STRATEGY_NAME);
            when(stockDeductionStrategy.deduct(1L)).thenReturn(new StockDeductionResult(1L));
            when(productRepository.getReferenceById(1L)).thenReturn(product);
            when(reservationRepository.findByIdempotencyKey("request-1")).thenReturn(Optional.empty());
            when(reservationRepository.findByProduct_IdAndUserId(1L, 1001L)).thenReturn(Optional.empty());

            service = new PurchaseService(
                    stockDeductionStrategyResolver,
                    productRepository,
                    reservationRepository,
                    new PurchaseMetrics(meterRegistry),
                    new ReservationMetrics(meterRegistry),
                    purchaseFailureInjector,
                    stockCompensationService,
                    waitingRoomService
            );
        }
    }
}
