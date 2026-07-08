package com.limitedgoodsreservation.reservation.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class ReservationMetrics {

    private final MeterRegistry meterRegistry;

    public ReservationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void incrementAttempt(String strategyName) {
        counter("reservation.attempts", strategyName).increment();
    }

    public void incrementSuccess(String strategyName) {
        counter("reservation.success", strategyName).increment();
    }

    public void incrementIdempotencyHit(String strategyName) {
        counter("reservation.idempotency.hit", strategyName).increment();
    }

    public void incrementDuplicateRejected(String strategyName) {
        counter("reservation.duplicate.rejected", strategyName).increment();
    }

    public void incrementCompensationSuccess(String strategyName) {
        counter("reservation.compensation.success", strategyName).increment();
    }

    public void incrementCompensationFailure(String strategyName) {
        counter("reservation.compensation.failure", strategyName).increment();
    }

    public void incrementActiveTokenRestored(String strategyName) {
        counter("reservation.active-token.restored", strategyName).increment();
    }

    public <T> T recordSave(String strategyName, Supplier<T> supplier) {
        return meterRegistry.timer("reservation.save.duration", "strategy", strategyName).record(supplier);
    }

    private Counter counter(String name, String strategyName) {
        return meterRegistry.counter(name, "strategy", strategyName);
    }
}
