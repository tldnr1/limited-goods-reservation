package com.limitedgoodsreservation.waitingroom.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class WaitingRoomMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicLong queueSize = new AtomicLong();
    private final AtomicLong activeTokenCount = new AtomicLong();

    public WaitingRoomMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge("waiting.queue.size", queueSize);
        meterRegistry.gauge("active.token.current", activeTokenCount);
    }

    public void incrementEnter() {
        meterRegistry.counter("waiting.enter").increment();
    }

    public void incrementDuplicateEnter() {
        meterRegistry.counter("waiting.duplicate.enter").increment();
    }

    public void incrementActiveTokenIssued(int count) {
        if (count > 0) {
            meterRegistry.counter("active.token.issued").increment(count);
        }
    }

    public void incrementPurchaseGuardRejection() {
        meterRegistry.counter("active.token.rejected").increment();
        meterRegistry.counter("purchase.guard.rejected").increment();
    }

    public void recordQueueSize(long value) {
        queueSize.set(value);
    }

    public void recordActiveTokenCount(long value) {
        activeTokenCount.set(value);
    }
}
