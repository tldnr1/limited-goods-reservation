package com.limitedgoodsreservation.waitingroom.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.limitedgoodsreservation.waitingroom.metrics.WaitingRoomMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WaitingRoomServiceTest {

    @Test
    void recordsDuplicateEnterWithoutCreatingNewQueueIntent() {
        CapturingStore store = new CapturingStore(new WaitingRoomEntry(
                1L,
                1001L,
                WaitingRoomStatus.WAITING,
                1L,
                1L,
                true
        ));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WaitingRoomService service = new WaitingRoomService(
                store,
                new WaitingRoomProperties(),
                new WaitingRoomMetrics(meterRegistry)
        );

        WaitingRoomEntry entry = service.enter(1001L, 1L);

        assertThat(entry.duplicate()).isTrue();
        assertThat(meterRegistry.counter("waiting.duplicate.enter").count()).isEqualTo(1);
    }

    @Test
    void admitsWithConfiguredHybridPolicyValues() {
        CapturingStore store = new CapturingStore(new AdmissionResult(20, 80, 100));
        WaitingRoomProperties properties = new WaitingRoomProperties();
        properties.getAdmission().setBatchSize(20);
        properties.getAdmission().setActiveCapacity(100);
        properties.getAdmission().setTokenTtlSeconds(60);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WaitingRoomService service = new WaitingRoomService(
                store,
                properties,
                new WaitingRoomMetrics(meterRegistry)
        );

        AdmissionResult result = service.admit(1L);

        assertThat(result.issuedCount()).isEqualTo(20);
        assertThat(store.batchSize).isEqualTo(20);
        assertThat(store.activeCapacity).isEqualTo(100);
        assertThat(store.tokenTtl).isEqualTo(Duration.ofSeconds(60));
        assertThat(meterRegistry.counter("active.token.issued").count()).isEqualTo(20);
    }

    @Test
    void rejectsPurchaseWhenActiveTokenIsMissing() {
        CapturingStore store = new CapturingStore(false);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WaitingRoomService service = new WaitingRoomService(
                store,
                new WaitingRoomProperties(),
                new WaitingRoomMetrics(meterRegistry)
        );

        assertThatThrownBy(() -> service.consumeActiveTokenOrThrow(1001L, 1L))
                .isInstanceOf(ActiveTokenRequiredException.class);
        assertThat(meterRegistry.counter("active.token.rejected").count()).isEqualTo(1);
        assertThat(meterRegistry.counter("purchase.guard.rejected").count()).isEqualTo(1);
    }

    @Test
    void skipsPurchaseGuardWhenWaitingRoomIsDisabled() {
        CapturingStore store = new CapturingStore(false);
        WaitingRoomProperties properties = new WaitingRoomProperties();
        properties.setEnabled(false);
        WaitingRoomService service = new WaitingRoomService(
                store,
                properties,
                new WaitingRoomMetrics(new SimpleMeterRegistry())
        );

        service.consumeActiveTokenOrThrow(1001L, 1L);

        assertThat(store.consumeCalled).isFalse();
    }

    @Test
    void restoresActiveTokenWithConfiguredTtl() {
        CapturingStore store = new CapturingStore(false);
        WaitingRoomProperties properties = new WaitingRoomProperties();
        properties.getAdmission().setTokenTtlSeconds(45);
        WaitingRoomService service = new WaitingRoomService(
                store,
                properties,
                new WaitingRoomMetrics(new SimpleMeterRegistry())
        );

        service.restoreActiveToken(1001L, 1L);

        assertThat(store.restoredProductId).isEqualTo(1L);
        assertThat(store.restoredUserId).isEqualTo(1001L);
        assertThat(store.restoredTokenTtl).isEqualTo(Duration.ofSeconds(45));
    }

    private static class CapturingStore implements WaitingRoomStore {

        private final WaitingRoomEntry entry;
        private final AdmissionResult admissionResult;
        private final boolean consumeResult;
        private int batchSize;
        private int activeCapacity;
        private Duration tokenTtl;
        private boolean consumeCalled;
        private Long restoredProductId;
        private Long restoredUserId;
        private Duration restoredTokenTtl;

        private CapturingStore(WaitingRoomEntry entry) {
            this.entry = entry;
            this.admissionResult = new AdmissionResult(0, 0, 0);
            this.consumeResult = false;
        }

        private CapturingStore(AdmissionResult admissionResult) {
            this.entry = new WaitingRoomEntry(1L, 1001L, WaitingRoomStatus.WAITING, 1L, 1L, false);
            this.admissionResult = admissionResult;
            this.consumeResult = false;
        }

        private CapturingStore(boolean consumeResult) {
            this.entry = new WaitingRoomEntry(1L, 1001L, WaitingRoomStatus.WAITING, 1L, 1L, false);
            this.admissionResult = new AdmissionResult(0, 0, 0);
            this.consumeResult = consumeResult;
        }

        @Override
        public WaitingRoomEntry enter(Long productId, Long userId) {
            return entry;
        }

        @Override
        public WaitingRoomEntry status(Long productId, Long userId) {
            return entry;
        }

        @Override
        public AdmissionResult admit(Long productId, int batchSize, int activeCapacity, Duration tokenTtl) {
            this.batchSize = batchSize;
            this.activeCapacity = activeCapacity;
            this.tokenTtl = tokenTtl;
            return admissionResult;
        }

        @Override
        public boolean consumeActiveToken(Long productId, Long userId) {
            consumeCalled = true;
            return consumeResult;
        }

        @Override
        public void restoreActiveToken(Long productId, Long userId, Duration tokenTtl) {
            restoredProductId = productId;
            restoredUserId = userId;
            restoredTokenTtl = tokenTtl;
        }
    }
}
