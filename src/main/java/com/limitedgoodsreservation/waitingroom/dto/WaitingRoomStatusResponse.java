package com.limitedgoodsreservation.waitingroom.dto;

import com.limitedgoodsreservation.waitingroom.service.WaitingRoomEntry;
import com.limitedgoodsreservation.waitingroom.service.WaitingRoomStatus;

public record WaitingRoomStatusResponse(
        Long productId,
        Long userId,
        String status,
        Long rank,
        Long queueSize,
        Integer retryAfterSeconds
) {

    public static WaitingRoomStatusResponse from(WaitingRoomEntry entry, int retryAfterSeconds) {
        return new WaitingRoomStatusResponse(
                entry.productId(),
                entry.userId(),
                entry.status().name(),
                entry.rank(),
                entry.queueSize(),
                entry.status() == WaitingRoomStatus.WAITING ? retryAfterSeconds : null
        );
    }
}
