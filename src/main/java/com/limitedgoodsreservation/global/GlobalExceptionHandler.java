package com.limitedgoodsreservation.global;

import com.limitedgoodsreservation.purchase.failure.InjectedPurchaseFailureException;
import com.limitedgoodsreservation.stock.strategy.StockDeductionException;
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("BAD_REQUEST", exception.getMessage()));
    }
}
