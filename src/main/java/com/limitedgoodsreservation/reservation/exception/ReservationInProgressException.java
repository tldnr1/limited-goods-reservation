package com.limitedgoodsreservation.reservation.exception;

public class ReservationInProgressException extends RuntimeException {

    public ReservationInProgressException(String idempotencyKey) {
        super("Reservation is already processing. idempotencyKey=" + idempotencyKey);
    }
}
