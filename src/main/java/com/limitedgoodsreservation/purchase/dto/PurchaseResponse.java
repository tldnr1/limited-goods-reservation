package com.limitedgoodsreservation.purchase.dto;

import com.limitedgoodsreservation.order.entity.Order;

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
