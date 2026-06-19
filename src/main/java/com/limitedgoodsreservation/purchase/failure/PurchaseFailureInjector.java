package com.limitedgoodsreservation.purchase.failure;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PurchaseFailureInjector {

    private static final String AFTER_STOCK_DECISION_BEFORE_ORDER_SAVE = "AFTER_STOCK_DECISION_BEFORE_ORDER_SAVE";

    private final String mode;
    private final int limit;
    private final ConcurrentMap<String, AtomicInteger> failureCounters = new ConcurrentHashMap<>();

    public PurchaseFailureInjector(
            @Value("${purchase.failure.mode:off}") String mode,
            @Value("${purchase.failure.limit:0}") int limit
    ) {
        this.mode = mode;
        this.limit = limit;
    }

    public void maybeFailAfterStockDecisionBeforeOrderSave(String runId, String strategyName) {
        if (!AFTER_STOCK_DECISION_BEFORE_ORDER_SAVE.equalsIgnoreCase(mode) || limit <= 0) {
            return;
        }

        String counterKey = "%s:%s".formatted(normalize(runId), strategyName);
        int count = failureCounters.computeIfAbsent(counterKey, ignored -> new AtomicInteger())
                .incrementAndGet();
        if (count <= limit) {
            throw new InjectedPurchaseFailureException(
                    "Injected purchase failure after stock decision. runId=%s strategy=%s count=%d limit=%d"
                            .formatted(normalize(runId), strategyName, count, limit)
            );
        }
    }

    private String normalize(String runId) {
        if (runId == null || runId.isBlank()) {
            return "default";
        }
        return runId;
    }
}
