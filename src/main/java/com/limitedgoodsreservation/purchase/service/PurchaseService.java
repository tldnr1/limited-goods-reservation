package com.limitedgoodsreservation.purchase.service;

import com.limitedgoodsreservation.product.entity.Product;
import com.limitedgoodsreservation.product.repository.ProductRepository;
import com.limitedgoodsreservation.purchase.dto.PurchaseResult;
import com.limitedgoodsreservation.purchase.dto.PurchaseResponse;
import com.limitedgoodsreservation.purchase.failure.InjectedPurchaseFailureException;
import com.limitedgoodsreservation.purchase.failure.PurchaseFailureInjector;
import com.limitedgoodsreservation.purchase.metrics.PurchaseMetrics;
import com.limitedgoodsreservation.reservation.entity.Reservation;
import com.limitedgoodsreservation.reservation.exception.AlreadyReservedException;
import com.limitedgoodsreservation.reservation.exception.IdempotencyKeyConflictException;
import com.limitedgoodsreservation.reservation.exception.ReservationFailedRetryableException;
import com.limitedgoodsreservation.reservation.metrics.ReservationMetrics;
import com.limitedgoodsreservation.reservation.repository.ReservationRepository;
import com.limitedgoodsreservation.stock.strategy.StockCompensationService;
import com.limitedgoodsreservation.stock.strategy.StockDeductionException;
import com.limitedgoodsreservation.stock.strategy.StockDeductionFailureReason;
import com.limitedgoodsreservation.stock.strategy.StockDeductionResult;
import com.limitedgoodsreservation.stock.strategy.StockDeductionStrategy;
import com.limitedgoodsreservation.stock.strategy.StockDeductionStrategyResolver;
import com.limitedgoodsreservation.waitingroom.service.WaitingRoomService;
import jakarta.annotation.PostConstruct;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseService {

    private final StockDeductionStrategyResolver stockDeductionStrategyResolver;
    private final ProductRepository productRepository;
    private final ReservationRepository reservationRepository;
    private final PurchaseMetrics purchaseMetrics;
    private final ReservationMetrics reservationMetrics;
    private final PurchaseFailureInjector purchaseFailureInjector;
    private final StockCompensationService stockCompensationService;
    private final WaitingRoomService waitingRoomService;

    public PurchaseService(
            StockDeductionStrategyResolver stockDeductionStrategyResolver,
            ProductRepository productRepository,
            ReservationRepository reservationRepository,
            PurchaseMetrics purchaseMetrics,
            ReservationMetrics reservationMetrics,
            PurchaseFailureInjector purchaseFailureInjector,
            StockCompensationService stockCompensationService,
            WaitingRoomService waitingRoomService
    ) {
        this.stockDeductionStrategyResolver = stockDeductionStrategyResolver;
        this.productRepository = productRepository;
        this.reservationRepository = reservationRepository;
        this.purchaseMetrics = purchaseMetrics;
        this.reservationMetrics = reservationMetrics;
        this.purchaseFailureInjector = purchaseFailureInjector;
        this.stockCompensationService = stockCompensationService;
        this.waitingRoomService = waitingRoomService;
    }

    @PostConstruct
    void initializeMetrics() {
        purchaseMetrics.initialize(stockDeductionStrategyResolver.selectedStrategyName());
    }

    @Transactional
    public PurchaseResult purchase(Long userId, Long productId, String runId, String idempotencyKey) {
        if (userId == null) {
            throw new IllegalArgumentException("X-USER-ID is required.");
        }
        if (productId == null) {
            throw new IllegalArgumentException("productId is required.");
        }
        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);

        StockDeductionStrategy stockDeductionStrategy = stockDeductionStrategyResolver.selectedStrategy();
        String strategyName = stockDeductionStrategy.strategyName();
        purchaseMetrics.incrementAttempt(strategyName);
        reservationMetrics.incrementAttempt(strategyName);

        Optional<Reservation> idempotentReservation =
                reservationRepository.findByIdempotencyKey(normalizedIdempotencyKey);
        if (idempotentReservation.isPresent()) {
            Reservation reservation = idempotentReservation.get();
            if (!reservation.matches(userId, productId)) {
                throw new IdempotencyKeyConflictException(normalizedIdempotencyKey);
            }
            reservationMetrics.incrementIdempotencyHit(strategyName);
            return new PurchaseResult(PurchaseResponse.from(reservation), false);
        }

        if (reservationRepository.findByProduct_IdAndUserId(productId, userId).isPresent()) {
            reservationMetrics.incrementDuplicateRejected(strategyName);
            throw new AlreadyReservedException(productId, userId);
        }

        waitingRoomService.consumeActiveTokenOrThrow(userId, productId);

        try {
            StockDeductionResult deductionResult = purchaseMetrics.recordStockDecision(
                    strategyName,
                    () -> stockDeductionStrategy.deduct(productId)
            );
            Product product = productRepository.getReferenceById(deductionResult.productId());
            Reservation reservation = saveReservationAfterStockDecision(
                    userId,
                    productId,
                    normalizedIdempotencyKey,
                    runId,
                    strategyName,
                    product
            );
            purchaseMetrics.incrementSuccess(strategyName);
            reservationMetrics.incrementSuccess(strategyName);

            return new PurchaseResult(PurchaseResponse.from(reservation), true);
        } catch (StockDeductionException exception) {
            purchaseMetrics.incrementFailure(strategyName, exception.reason());
            throw exception;
        }
    }

    private Reservation saveReservationAfterStockDecision(
            Long userId,
            Long productId,
            String idempotencyKey,
            String runId,
            String strategyName,
            Product product
    ) {
        try {
            purchaseFailureInjector.maybeFailAfterStockDecisionBeforeReservationSave(runId, strategyName);
            return reservationMetrics.recordSave(
                    strategyName,
                    () -> reservationRepository.saveAndFlush(Reservation.reserved(userId, product, idempotencyKey))
            );
        } catch (RuntimeException exception) {
            handleReservationPersistenceFailure(userId, productId, strategyName, exception);
            throw new ReservationFailedRetryableException(productId, userId, exception);
        }
    }

    private void handleReservationPersistenceFailure(
            Long userId,
            Long productId,
            String strategyName,
            RuntimeException exception
    ) {
        purchaseMetrics.incrementFailure(strategyName, failureReason(exception));

        boolean compensated = stockCompensationService.compensate(strategyName, productId);
        if (compensated) {
            reservationMetrics.incrementCompensationSuccess(strategyName);
            waitingRoomService.restoreActiveToken(userId, productId);
            reservationMetrics.incrementActiveTokenRestored(strategyName);
            return;
        }

        reservationMetrics.incrementCompensationFailure(strategyName);
    }

    private StockDeductionFailureReason failureReason(RuntimeException exception) {
        if (exception instanceof InjectedPurchaseFailureException) {
            return StockDeductionFailureReason.INJECTED_ORDER_SAVE_FAILURE;
        }
        return StockDeductionFailureReason.RESERVATION_SAVE_FAILURE;
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("X-IDEMPOTENCY-KEY is required.");
        }

        String normalized = idempotencyKey.trim();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException("X-IDEMPOTENCY-KEY must be 100 characters or less.");
        }
        return normalized;
    }
}
