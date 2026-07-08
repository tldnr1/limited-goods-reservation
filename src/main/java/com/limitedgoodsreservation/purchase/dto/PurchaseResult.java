package com.limitedgoodsreservation.purchase.dto;

public record PurchaseResult(
        PurchaseResponse response,
        boolean created
) {
}
