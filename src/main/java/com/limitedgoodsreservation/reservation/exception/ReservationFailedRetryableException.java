package com.limitedgoodsreservation.reservation.exception;

public class ReservationFailedRetryableException extends RuntimeException {

    public ReservationFailedRetryableException(Long productId, Long userId, Throwable cause) {
        super("Reservation failed after stock decision and was handled as retryable. productId="
                + productId + " userId=" + userId, cause);
    }
}
