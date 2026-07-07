package com.limitedgoodsreservation.waitingroom.service;

public record AdmissionResult(
        int issuedCount,
        long queueSize,
        long activeTokenCount
) {
}
