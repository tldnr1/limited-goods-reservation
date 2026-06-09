package com.limitedgoodsreservation.stock.application;

public class SoldOutException extends RuntimeException {

    public SoldOutException(Long productId) {
        super("Sold out. productId=" + productId);
    }
}
