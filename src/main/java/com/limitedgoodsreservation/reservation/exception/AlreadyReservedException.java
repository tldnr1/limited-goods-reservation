package com.limitedgoodsreservation.reservation.exception;

public class AlreadyReservedException extends RuntimeException {

    public AlreadyReservedException(Long productId, Long userId) {
        super("User already has a reservation. productId=" + productId + " userId=" + userId);
    }
}
