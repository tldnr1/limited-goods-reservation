package com.limitedgoodsreservation.stock.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stock")
public record StockStrategyProperties(String strategy) {

    public String selectedStrategy() {
        if (strategy == null || strategy.isBlank()) {
            return NaiveRdbStockStrategy.STRATEGY_NAME;
        }
        return strategy;
    }
}
