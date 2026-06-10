package com.limitedgoodsreservation.stock.application;

public enum StockDeductionFailureReason {
    SOLD_OUT,
    OPTIMISTIC_CONFLICT,
    LOCK_BUSY,
    LOCK_TIMEOUT,
    UNEXPECTED_FAILURE
}
