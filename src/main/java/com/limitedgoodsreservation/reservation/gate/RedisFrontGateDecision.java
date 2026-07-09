package com.limitedgoodsreservation.reservation.gate;

import java.util.Arrays;

public enum RedisFrontGateDecision {

    ACCEPTED(1L),
    ACTIVE_TOKEN_REQUIRED(-1L),
    IDEMPOTENCY_PROCESSING(-2L),
    IDEMPOTENCY_RESERVED(-3L),
    ALREADY_RESERVED(-4L),
    SOLD_OUT(-5L),
    MISSING_STOCK_KEY(-6L);

    private final Long code;

    RedisFrontGateDecision(Long code) {
        this.code = code;
    }

    public static RedisFrontGateDecision fromCode(Long code) {
        return Arrays.stream(values())
                .filter(decision -> decision.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown Redis front-gate result: " + code));
    }

    public Long code() {
        return code;
    }
}
