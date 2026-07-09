package com.limitedgoodsreservation.purchase.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "purchase")
public record PurchaseArchitectureProperties(String architecture) {

    public String selectedArchitecture() {
        if (architecture == null || architecture.isBlank()) {
            return LegacyStockStrategyPurchaseFlow.ARCHITECTURE_NAME;
        }
        return architecture.trim();
    }
}
