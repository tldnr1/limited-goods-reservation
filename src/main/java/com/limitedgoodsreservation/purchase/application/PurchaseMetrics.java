package com.limitedgoodsreservation.purchase.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class PurchaseMetrics {

    private final MeterRegistry meterRegistry;

    public PurchaseMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void initialize(String strategyName) {
        counter("purchase.attempts", strategyName);
        counter("purchase.success", strategyName);
        counter("purchase.sold.out", strategyName);
        counter("purchase.unexpected.failure", strategyName);
    }

    public void incrementAttempt(String strategyName) {
        increment("purchase.attempts", strategyName);
    }

    public void incrementSuccess(String strategyName) {
        increment("purchase.success", strategyName);
    }

    public void incrementSoldOut(String strategyName) {
        increment("purchase.sold.out", strategyName);
    }

    public void incrementUnexpectedFailure(String strategyName) {
        increment("purchase.unexpected.failure", strategyName);
    }

    private void increment(String name, String strategyName) {
        counter(name, strategyName).increment();
    }

    private Counter counter(String name, String strategyName) {
        return meterRegistry.counter(name, "strategy", strategyName);
    }
}
