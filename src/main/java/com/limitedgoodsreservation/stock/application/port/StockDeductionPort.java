package com.limitedgoodsreservation.stock.application.port;

public interface StockDeductionPort {

    String strategyName();

    StockDeductionResult deduct(Long productId);
}
