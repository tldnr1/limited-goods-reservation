package com.limitedgoodsreservation.waitingroom.service;

public class ActiveTokenRequiredException extends RuntimeException {

    public ActiveTokenRequiredException(Long productId, Long userId) {
        super("Active token is required. productId=" + productId + " userId=" + userId);
    }
}
