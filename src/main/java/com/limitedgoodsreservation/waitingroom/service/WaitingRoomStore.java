package com.limitedgoodsreservation.waitingroom.service;

import java.time.Duration;

public interface WaitingRoomStore {

    WaitingRoomEntry enter(Long productId, Long userId);

    WaitingRoomEntry status(Long productId, Long userId);

    AdmissionResult admit(Long productId, int batchSize, int activeCapacity, Duration tokenTtl);

    boolean consumeActiveToken(Long productId, Long userId);

    void restoreActiveToken(Long productId, Long userId, Duration tokenTtl);
}
