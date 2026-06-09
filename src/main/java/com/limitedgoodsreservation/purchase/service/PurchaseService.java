package com.limitedgoodsreservation.purchase.service;

import com.limitedgoodsreservation.order.entity.Order;
import com.limitedgoodsreservation.order.repository.OrderRepository;
import com.limitedgoodsreservation.product.entity.ProductStock;
import com.limitedgoodsreservation.product.repository.ProductStockRepository;
import com.limitedgoodsreservation.purchase.dto.PurchaseResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseService {

    private final ProductStockRepository productStockRepository;
    private final OrderRepository orderRepository;

    public PurchaseService(
            ProductStockRepository productStockRepository,
            OrderRepository orderRepository
    ) {
        this.productStockRepository = productStockRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public PurchaseResponse purchase(Long userId, Long productId) {
        if (userId == null) {
            throw new IllegalArgumentException("X-USER-ID is required.");
        }
        if (productId == null) {
            throw new IllegalArgumentException("productId is required.");
        }

        ProductStock stock = productStockRepository.findByProduct_Id(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found. productId=" + productId));

        if (!stock.hasAvailableQuantity()) {
            throw new SoldOutException(productId);
        }

        Order order = orderRepository.save(Order.created(userId, stock.getProduct()));
        stock.increaseSoldQuantity();

        return PurchaseResponse.from(order);
    }
}
