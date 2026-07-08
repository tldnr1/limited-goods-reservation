package com.limitedgoodsreservation.global;

import com.limitedgoodsreservation.purchase.failure.InjectedPurchaseFailureException;
import com.limitedgoodsreservation.reservation.exception.AlreadyReservedException;
import com.limitedgoodsreservation.reservation.exception.IdempotencyKeyConflictException;
import com.limitedgoodsreservation.reservation.exception.ReservationFailedRetryableException;
import com.limitedgoodsreservation.stock.strategy.StockDeductionException;
import com.limitedgoodsreservation.waitingroom.service.ActiveTokenRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(StockDeductionException.class)
    public ResponseEntity<ErrorResponse> handleStockDeduction(StockDeductionException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(exception.reason().name(), exception.getMessage()));
    }

    @ExceptionHandler(InjectedPurchaseFailureException.class)
    public ResponseEntity<ErrorResponse> handleInjectedPurchaseFailure(InjectedPurchaseFailureException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("INJECTED_ORDER_SAVE_FAILURE", exception.getMessage()));
    }

    @ExceptionHandler(ActiveTokenRequiredException.class)
    public ResponseEntity<ErrorResponse> handleActiveTokenRequired(ActiveTokenRequiredException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("ACTIVE_TOKEN_REQUIRED", exception.getMessage()));
    }

    @ExceptionHandler(AlreadyReservedException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyReserved(AlreadyReservedException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("ALREADY_RESERVED", exception.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyKeyConflict(IdempotencyKeyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("IDEMPOTENCY_KEY_CONFLICT", exception.getMessage()));
    }

    @ExceptionHandler(ReservationFailedRetryableException.class)
    public ResponseEntity<ErrorResponse> handleReservationFailedRetryable(ReservationFailedRetryableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("RESERVATION_FAILED_RETRYABLE", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("BAD_REQUEST", exception.getMessage()));
    }
}
