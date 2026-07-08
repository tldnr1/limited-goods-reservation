package com.limitedgoodsreservation.purchase.failure;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PurchaseFailureInjector {

    private static final String AFTER_STOCK_DECISION_BEFORE_ORDER_SAVE = "AFTER_STOCK_DECISION_BEFORE_ORDER_SAVE";
    private static final String AFTER_STOCK_DECISION_BEFORE_RESERVATION_SAVE =
            "AFTER_STOCK_DECISION_BEFORE_RESERVATION_SAVE";

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
        maybeFailAfterStockDecisionBeforeReservationSave(runId, strategyName);
    }

    public void maybeFailAfterStockDecisionBeforeReservationSave(String runId, String strategyName) {
        if (!isPersistenceFailureMode() || limit <= 0) {
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

    private boolean isPersistenceFailureMode() {
        return AFTER_STOCK_DECISION_BEFORE_ORDER_SAVE.equalsIgnoreCase(mode)
                || AFTER_STOCK_DECISION_BEFORE_RESERVATION_SAVE.equalsIgnoreCase(mode);
    }

    private String normalize(String runId) {
        if (runId == null || runId.isBlank()) {
            return "default";
        }
        return runId;
    }
}
