package com.limitedgoodsreservation.purchase.dto;

import com.limitedgoodsreservation.reservation.entity.Reservation;

public record PurchaseResponse(
        Long reservationId,
        Long userId,
        Long productId,
        String status
) {

    public static PurchaseResponse from(Reservation reservation) {
        return new PurchaseResponse(
                reservation.getId(),
                reservation.getUserId(),
                reservation.getProductId(),
                reservation.getStatus().name()
        );
    }
}
