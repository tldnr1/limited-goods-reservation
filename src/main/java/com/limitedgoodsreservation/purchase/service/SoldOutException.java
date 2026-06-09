package com.limitedgoodsreservation.purchase.service;

public class SoldOutException extends RuntimeException {

    public SoldOutException(Long productId) {
        super("Sold out. productId=" + productId);
    }
}
