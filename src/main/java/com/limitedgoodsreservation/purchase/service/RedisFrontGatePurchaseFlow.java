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
import com.limitedgoodsreservation.reservation.exception.ReservationInProgressException;
import com.limitedgoodsreservation.reservation.gate.RedisFrontGateDecision;
import com.limitedgoodsreservation.reservation.gate.RedisReservationFrontGate;
import com.limitedgoodsreservation.reservation.metrics.ReservationMetrics;
import com.limitedgoodsreservation.reservation.repository.ReservationRepository;
import com.limitedgoodsreservation.stock.strategy.SoldOutException;
import com.limitedgoodsreservation.stock.strategy.StockDeductionException;
import com.limitedgoodsreservation.stock.strategy.StockDeductionFailureReason;
import com.limitedgoodsreservation.waitingroom.service.ActiveTokenRequiredException;
import com.limitedgoodsreservation.waitingroom.service.WaitingRoomService;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RedisFrontGatePurchaseFlow implements PurchaseFlow {

    public static final String ARCHITECTURE_NAME = "redis-frontgate";

    private final RedisReservationFrontGate redisReservationFrontGate;
    private final ProductRepository productRepository;
    private final ReservationRepository reservationRepository;
    private final PurchaseMetrics purchaseMetrics;
    private final ReservationMetrics reservationMetrics;
    private final PurchaseFailureInjector purchaseFailureInjector;
    private final WaitingRoomService waitingRoomService;
    private final TransactionTemplate transactionTemplate;

    public RedisFrontGatePurchaseFlow(
            RedisReservationFrontGate redisReservationFrontGate,
            ProductRepository productRepository,
            ReservationRepository reservationRepository,
            PurchaseMetrics purchaseMetrics,
            ReservationMetrics reservationMetrics,
            PurchaseFailureInjector purchaseFailureInjector,
            WaitingRoomService waitingRoomService,
            PlatformTransactionManager transactionManager
    ) {
        this.redisReservationFrontGate = redisReservationFrontGate;
        this.productRepository = productRepository;
        this.reservationRepository = reservationRepository;
        this.purchaseMetrics = purchaseMetrics;
        this.reservationMetrics = reservationMetrics;
        this.purchaseFailureInjector = purchaseFailureInjector;
        this.waitingRoomService = waitingRoomService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public boolean supports(String architecture) {
        return ARCHITECTURE_NAME.equals(architecture);
    }

    @Override
    public String metricsName() {
        return ARCHITECTURE_NAME;
    }

    @Override
    public PurchaseResult purchase(Long userId, Long productId, String runId, String idempotencyKey) {
        purchaseMetrics.incrementAttempt(ARCHITECTURE_NAME);
        reservationMetrics.incrementAttempt(ARCHITECTURE_NAME);

        RedisFrontGateDecision decision = purchaseMetrics.recordStockDecision(
                ARCHITECTURE_NAME,
                () -> redisReservationFrontGate.enter(userId, productId, idempotencyKey)
        );
        if (decision != RedisFrontGateDecision.ACCEPTED) {
            reservationMetrics.incrementFrontGateRejected(ARCHITECTURE_NAME, decision.name());
            return handleRejectedDecision(userId, productId, idempotencyKey, decision);
        }

        reservationMetrics.incrementFrontGateAccepted(ARCHITECTURE_NAME);
        try {
            Reservation reservation = transactionTemplate.execute(status ->
                    saveReservationAfterFrontGate(userId, productId, idempotencyKey, runId)
            );
            redisReservationFrontGate.finalizeReservation(userId, productId, idempotencyKey, reservation.getId());
            purchaseMetrics.incrementSuccess(ARCHITECTURE_NAME);
            reservationMetrics.incrementSuccess(ARCHITECTURE_NAME);

            return new PurchaseResult(PurchaseResponse.from(reservation), true);
        } catch (ReservationPersistenceFailure exception) {
            handleReservationPersistenceFailure(userId, productId, idempotencyKey, exception.getCause());
            throw new ReservationFailedRetryableException(productId, userId, exception.getCause());
        }
    }

    private PurchaseResult handleRejectedDecision(
            Long userId,
            Long productId,
            String idempotencyKey,
            RedisFrontGateDecision decision
    ) {
        return switch (decision) {
            case ACTIVE_TOKEN_REQUIRED -> throw new ActiveTokenRequiredException(productId, userId);
            case IDEMPOTENCY_PROCESSING -> throw new ReservationInProgressException(idempotencyKey);
            case IDEMPOTENCY_RESERVED -> handleIdempotencyReserved(userId, productId, idempotencyKey);
            case ALREADY_RESERVED -> {
                reservationMetrics.incrementDuplicateRejected(ARCHITECTURE_NAME);
                throw new AlreadyReservedException(productId, userId);
            }
            case SOLD_OUT -> throw new SoldOutException(productId);
            case MISSING_STOCK_KEY -> throw new StockDeductionException(
                    StockDeductionFailureReason.UNEXPECTED_FAILURE,
                    "Redis stock key missing. productId=" + productId
            );
            case ACCEPTED -> throw new IllegalStateException("ACCEPTED is not a rejected decision.");
        };
    }

    private PurchaseResult handleIdempotencyReserved(Long userId, Long productId, String idempotencyKey) {
        Optional<Reservation> idempotentReservation = reservationRepository.findByIdempotencyKey(idempotencyKey);
        if (idempotentReservation.isEmpty()) {
            throw new ReservationInProgressException(idempotencyKey);
        }

        Reservation reservation = idempotentReservation.get();
        if (!reservation.matches(userId, productId)) {
            throw new IdempotencyKeyConflictException(idempotencyKey);
        }

        reservationMetrics.incrementIdempotencyHit(ARCHITECTURE_NAME);
        return new PurchaseResult(PurchaseResponse.from(reservation), false);
    }

    private Reservation saveReservationAfterFrontGate(
            Long userId,
            Long productId,
            String idempotencyKey,
            String runId
    ) {
        try {
            purchaseFailureInjector.maybeFailAfterStockDecisionBeforeReservationSave(runId, ARCHITECTURE_NAME);
            Product product = productRepository.getReferenceById(productId);
            return reservationMetrics.recordSave(
                    ARCHITECTURE_NAME,
                    () -> reservationRepository.saveAndFlush(Reservation.reserved(userId, product, idempotencyKey))
            );
        } catch (RuntimeException exception) {
            throw new ReservationPersistenceFailure(exception);
        }
    }

    private void handleReservationPersistenceFailure(
            Long userId,
            Long productId,
            String idempotencyKey,
            RuntimeException exception
    ) {
        purchaseMetrics.incrementFailure(ARCHITECTURE_NAME, failureReason(exception));

        boolean compensated = redisReservationFrontGate.compensate(userId, productId, idempotencyKey);
        if (compensated) {
            reservationMetrics.incrementCompensationSuccess(ARCHITECTURE_NAME);
            waitingRoomService.restoreActiveToken(userId, productId);
            reservationMetrics.incrementActiveTokenRestored(ARCHITECTURE_NAME);
            return;
        }

        reservationMetrics.incrementCompensationFailure(ARCHITECTURE_NAME);
    }

    private StockDeductionFailureReason failureReason(RuntimeException exception) {
        if (exception instanceof InjectedPurchaseFailureException) {
            return StockDeductionFailureReason.INJECTED_ORDER_SAVE_FAILURE;
        }
        return StockDeductionFailureReason.RESERVATION_SAVE_FAILURE;
    }

    private static class ReservationPersistenceFailure extends RuntimeException {

        private ReservationPersistenceFailure(RuntimeException cause) {
            super(cause);
        }

        @Override
        public RuntimeException getCause() {
            return (RuntimeException) super.getCause();
        }
    }
}
