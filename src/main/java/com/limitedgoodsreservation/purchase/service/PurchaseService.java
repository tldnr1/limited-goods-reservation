package com.limitedgoodsreservation.purchase.service;

import com.limitedgoodsreservation.purchase.dto.PurchaseResult;
import com.limitedgoodsreservation.purchase.metrics.PurchaseMetrics;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

@Service
public class PurchaseService {

    private final PurchaseFlowSelector purchaseFlowSelector;
    private final PurchaseMetrics purchaseMetrics;

    public PurchaseService(
            PurchaseFlowSelector purchaseFlowSelector,
            PurchaseMetrics purchaseMetrics
    ) {
        this.purchaseFlowSelector = purchaseFlowSelector;
        this.purchaseMetrics = purchaseMetrics;
    }

    @PostConstruct
    void initializeMetrics() {
        purchaseMetrics.initialize(purchaseFlowSelector.selectedMetricsName());
    }

    public PurchaseResult purchase(Long userId, Long productId, String runId, String idempotencyKey) {
        if (userId == null) {
            throw new IllegalArgumentException("X-USER-ID is required.");
        }
        if (productId == null) {
            throw new IllegalArgumentException("productId is required.");
        }
        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);

        return purchaseFlowSelector.selectedFlow().purchase(userId, productId, runId, normalizedIdempotencyKey);
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("X-IDEMPOTENCY-KEY is required.");
        }

        String normalized = idempotencyKey.trim();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException("X-IDEMPOTENCY-KEY must be 100 characters or less.");
        }
        return normalized;
    }
}
