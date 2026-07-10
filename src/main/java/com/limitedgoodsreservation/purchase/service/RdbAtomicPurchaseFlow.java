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
import com.limitedgoodsreservation.stock.strategy.RdbAtomicStockStrategy;
import com.limitedgoodsreservation.stock.strategy.StockDeductionException;
import com.limitedgoodsreservation.stock.strategy.StockDeductionFailureReason;
import com.limitedgoodsreservation.stock.strategy.StockDeductionResult;
import com.limitedgoodsreservation.waitingroom.service.WaitingRoomService;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RdbAtomicPurchaseFlow implements PurchaseFlow {

    public static final String ARCHITECTURE_NAME = "rdb-atomic";

    private final RdbAtomicStockStrategy rdbAtomicStockStrategy;
    private final ProductRepository productRepository;
    private final ReservationRepository reservationRepository;
    private final PurchaseMetrics purchaseMetrics;
    private final ReservationMetrics reservationMetrics;
    private final PurchaseFailureInjector purchaseFailureInjector;
    private final WaitingRoomService waitingRoomService;
    private final TransactionTemplate transactionTemplate;

    public RdbAtomicPurchaseFlow(
            RdbAtomicStockStrategy rdbAtomicStockStrategy,
            ProductRepository productRepository,
            ReservationRepository reservationRepository,
            PurchaseMetrics purchaseMetrics,
            ReservationMetrics reservationMetrics,
            PurchaseFailureInjector purchaseFailureInjector,
            WaitingRoomService waitingRoomService,
            PlatformTransactionManager transactionManager
    ) {
        this.rdbAtomicStockStrategy = rdbAtomicStockStrategy;
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

        waitingRoomService.consumeActiveTokenOrThrow(userId, productId);

        try {
            return transactionTemplate.execute(status -> purchaseInTransaction(userId, productId, runId, idempotencyKey));
        } catch (ReservationPersistenceFailure exception) {
            handleReservationPersistenceFailure(userId, productId, exception.getCause());
            throw new ReservationFailedRetryableException(productId, userId, exception.getCause());
        } catch (StockDeductionException exception) {
            purchaseMetrics.incrementFailure(ARCHITECTURE_NAME, exception.reason());
            throw exception;
        }
    }

    private PurchaseResult purchaseInTransaction(Long userId, Long productId, String runId, String idempotencyKey) {
        Optional<Reservation> idempotentReservation = reservationRepository.findByIdempotencyKey(idempotencyKey);
        if (idempotentReservation.isPresent()) {
            Reservation reservation = idempotentReservation.get();
            if (!reservation.matches(userId, productId)) {
                throw new IdempotencyKeyConflictException(idempotencyKey);
            }
            reservationMetrics.incrementIdempotencyHit(ARCHITECTURE_NAME);
            return new PurchaseResult(PurchaseResponse.from(reservation), false);
        }

        if (reservationRepository.findByProduct_IdAndUserId(productId, userId).isPresent()) {
            reservationMetrics.incrementDuplicateRejected(ARCHITECTURE_NAME);
            throw new AlreadyReservedException(productId, userId);
        }

        StockDeductionResult deductionResult = purchaseMetrics.recordStockDecision(
                ARCHITECTURE_NAME,
                () -> rdbAtomicStockStrategy.deduct(productId)
        );
        Product product = productRepository.getReferenceById(deductionResult.productId());
        Reservation reservation = saveReservationAfterStockDecision(userId, productId, idempotencyKey, runId, product);
        purchaseMetrics.incrementSuccess(ARCHITECTURE_NAME);
        reservationMetrics.incrementSuccess(ARCHITECTURE_NAME);

        return new PurchaseResult(PurchaseResponse.from(reservation), true);
    }

    private Reservation saveReservationAfterStockDecision(
            Long userId,
            Long productId,
            String idempotencyKey,
            String runId,
            Product product
    ) {
        try {
            purchaseFailureInjector.maybeFailAfterStockDecisionBeforeReservationSave(runId, ARCHITECTURE_NAME);
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
            RuntimeException exception
    ) {
        purchaseMetrics.incrementFailure(ARCHITECTURE_NAME, failureReason(exception));
        reservationMetrics.incrementCompensationSuccess(ARCHITECTURE_NAME);
        waitingRoomService.restoreActiveToken(userId, productId);
        reservationMetrics.incrementActiveTokenRestored(ARCHITECTURE_NAME);
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
