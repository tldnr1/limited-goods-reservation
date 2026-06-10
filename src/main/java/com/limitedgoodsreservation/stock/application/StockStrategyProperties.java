package com.limitedgoodsreservation.stock.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stock")
public record StockStrategyProperties(String strategy) {

    public String selectedStrategy() {
        if (strategy == null || strategy.isBlank()) {
            return "naive-rdb";
        }
        return strategy;
    }
}
