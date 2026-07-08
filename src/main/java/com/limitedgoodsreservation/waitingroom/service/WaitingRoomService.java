package com.limitedgoodsreservation.waitingroom.service;

import com.limitedgoodsreservation.waitingroom.metrics.WaitingRoomMetrics;
import java.time.Duration;
import org.springframework.stereotype.Service;

@Service
public class WaitingRoomService {

    private final WaitingRoomStore waitingRoomStore;
    private final WaitingRoomProperties properties;
    private final WaitingRoomMetrics waitingRoomMetrics;

    public WaitingRoomService(
            WaitingRoomStore waitingRoomStore,
            WaitingRoomProperties properties,
            WaitingRoomMetrics waitingRoomMetrics
    ) {
        this.waitingRoomStore = waitingRoomStore;
        this.properties = properties;
        this.waitingRoomMetrics = waitingRoomMetrics;
    }

    public WaitingRoomEntry enter(Long userId, Long productId) {
        validate(userId, productId);

        WaitingRoomEntry entry = waitingRoomStore.enter(productId, userId);
        if (entry.duplicate()) {
            waitingRoomMetrics.incrementDuplicateEnter();
        } else {
            waitingRoomMetrics.incrementEnter();
        }
        waitingRoomMetrics.recordQueueSize(entry.queueSize());
        return entry;
    }

    public WaitingRoomEntry status(Long userId, Long productId) {
        validate(userId, productId);

        WaitingRoomEntry entry = waitingRoomStore.status(productId, userId);
        waitingRoomMetrics.recordQueueSize(entry.queueSize());
        return entry;
    }

    public AdmissionResult admitDefaultProduct() {
        return admit(properties.getProductId());
    }

    public AdmissionResult admit(Long productId) {
        WaitingRoomProperties.Admission admission = properties.getAdmission();
        AdmissionResult result = waitingRoomStore.admit(
                productId,
                admission.getBatchSize(),
                admission.getActiveCapacity(),
                Duration.ofSeconds(admission.getTokenTtlSeconds())
        );
        waitingRoomMetrics.incrementActiveTokenIssued(result.issuedCount());
        waitingRoomMetrics.recordQueueSize(result.queueSize());
        waitingRoomMetrics.recordActiveTokenCount(result.activeTokenCount());
        return result;
    }

    public void consumeActiveTokenOrThrow(Long userId, Long productId) {
        validate(userId, productId);
        if (!properties.isEnabled()) {
            return;
        }

        boolean consumed = waitingRoomStore.consumeActiveToken(productId, userId);
        if (!consumed) {
            waitingRoomMetrics.incrementPurchaseGuardRejection();
            throw new ActiveTokenRequiredException(productId, userId);
        }
    }

    public void restoreActiveToken(Long userId, Long productId) {
        validate(userId, productId);
        if (!properties.isEnabled()) {
            return;
        }

        waitingRoomStore.restoreActiveToken(
                productId,
                userId,
                Duration.ofSeconds(properties.getAdmission().getTokenTtlSeconds())
        );
    }

    public int retryAfterSeconds() {
        return properties.getAdmission().getRetryAfterSeconds();
    }

    public boolean isAdmissionSchedulerEnabled() {
        return properties.getAdmission().isSchedulerEnabled();
    }

    private void validate(Long userId, Long productId) {
        if (userId == null) {
            throw new IllegalArgumentException("X-USER-ID is required.");
        }
        if (productId == null) {
            throw new IllegalArgumentException("productId is required.");
        }
    }
}
