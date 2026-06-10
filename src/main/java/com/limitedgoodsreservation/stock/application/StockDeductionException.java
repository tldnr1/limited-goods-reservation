package com.limitedgoodsreservation.stock.application;

public class StockDeductionException extends RuntimeException {

    private final StockDeductionFailureReason reason;

    public StockDeductionException(StockDeductionFailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public StockDeductionFailureReason reason() {
        return reason;
    }
}
