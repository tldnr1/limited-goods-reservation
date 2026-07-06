package com.limitedgoodsreservation.waitingroom.service;

public record WaitingRoomEntry(
        Long productId,
        Long userId,
        WaitingRoomStatus status,
        Long rank,
        Long queueSize,
        boolean duplicate
) {
}
