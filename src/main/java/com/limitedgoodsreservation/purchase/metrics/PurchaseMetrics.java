package com.limitedgoodsreservation.purchase.metrics;

import com.limitedgoodsreservation.stock.strategy.StockDeductionFailureReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
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
        timer("stock.decision.duration", strategyName);
        timer("order.save.duration", strategyName);
        for (StockDeductionFailureReason reason : StockDeductionFailureReason.values()) {
            failureCounter(strategyName, reason);
        }
    }

    public void incrementAttempt(String strategyName) {
        increment("purchase.attempts", strategyName);
    }

    public void incrementSuccess(String strategyName) {
        increment("purchase.success", strategyName);
    }

    public void incrementFailure(String strategyName, StockDeductionFailureReason reason) {
        failureCounter(strategyName, reason).increment();
    }

    public <T> T recordStockDecision(String strategyName, Supplier<T> supplier) {
        return timer("stock.decision.duration", strategyName).record(supplier);
    }

    public <T> T recordOrderSave(String strategyName, Supplier<T> supplier) {
        return timer("order.save.duration", strategyName).record(supplier);
    }

    private void increment(String name, String strategyName) {
        counter(name, strategyName).increment();
    }

    private Counter counter(String name, String strategyName) {
        return meterRegistry.counter(name, "strategy", strategyName);
    }

    private Timer timer(String name, String strategyName) {
        return meterRegistry.timer(name, "strategy", strategyName);
    }

    private Counter failureCounter(String strategyName, StockDeductionFailureReason reason) {
        return meterRegistry.counter("purchase.failure", "strategy", strategyName, "reason", reason.name());
    }
}
