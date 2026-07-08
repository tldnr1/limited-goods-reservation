package com.limitedgoodsreservation.reservation.exception;

public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("Idempotency key is already used for another request. idempotencyKey=" + idempotencyKey);
    }
}
