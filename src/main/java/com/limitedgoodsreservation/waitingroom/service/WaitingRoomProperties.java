package com.limitedgoodsreservation.waitingroom.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "waiting-room")
public class WaitingRoomProperties {

    private boolean enabled = true;
    private long productId = 1L;
    private final Admission admission = new Admission();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getProductId() {
        return productId;
    }

    public void setProductId(long productId) {
        this.productId = productId;
    }

    public Admission getAdmission() {
        return admission;
    }

    public static class Admission {

        private boolean schedulerEnabled = true;
        private int batchSize = 20;
        private int activeCapacity = 100;
        private long tokenTtlSeconds = 60;
        private long intervalMs = 1000;
        private int retryAfterSeconds = 2;

        public boolean isSchedulerEnabled() {
            return schedulerEnabled;
        }

        public void setSchedulerEnabled(boolean schedulerEnabled) {
            this.schedulerEnabled = schedulerEnabled;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getActiveCapacity() {
            return activeCapacity;
        }

        public void setActiveCapacity(int activeCapacity) {
            this.activeCapacity = activeCapacity;
        }

        public long getTokenTtlSeconds() {
            return tokenTtlSeconds;
        }

        public void setTokenTtlSeconds(long tokenTtlSeconds) {
            this.tokenTtlSeconds = tokenTtlSeconds;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public int getRetryAfterSeconds() {
            return retryAfterSeconds;
        }

        public void setRetryAfterSeconds(int retryAfterSeconds) {
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }
}
