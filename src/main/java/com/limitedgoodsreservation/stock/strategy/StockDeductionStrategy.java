package com.limitedgoodsreservation.stock.strategy;

public interface StockDeductionStrategy {

    String strategyName();

    StockDeductionResult deduct(Long productId);
}
