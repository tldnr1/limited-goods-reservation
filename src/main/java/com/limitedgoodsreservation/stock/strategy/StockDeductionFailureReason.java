package com.limitedgoodsreservation.stock.strategy;

public enum StockDeductionFailureReason {
    SOLD_OUT,
    LOCK_TIMEOUT,
    INJECTED_ORDER_SAVE_FAILURE,
    RESERVATION_SAVE_FAILURE,
    UNEXPECTED_FAILURE
}
