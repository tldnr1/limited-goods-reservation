package com.limitedgoodsreservation.purchase.adapter.in.web;

import com.limitedgoodsreservation.order.domain.Order;

public record PurchaseResponse(
        Long orderId,
        Long userId,
        Long productId,
        String status
) {

    public static PurchaseResponse from(Order order) {
        return new PurchaseResponse(
                order.getId(),
                order.getUserId(),
                order.getProductId(),
                order.getStatus().name()
        );
    }
}
