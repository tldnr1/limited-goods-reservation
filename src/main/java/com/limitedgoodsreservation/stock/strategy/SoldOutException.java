package com.limitedgoodsreservation.stock.strategy;

public class SoldOutException extends StockDeductionException {

    public SoldOutException(Long productId) {
        super(StockDeductionFailureReason.SOLD_OUT, "Sold out. productId=" + productId);
    }
}
