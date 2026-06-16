package com.limitedgoodsreservation.purchase.service;

import com.limitedgoodsreservation.order.entity.Order;
import com.limitedgoodsreservation.order.repository.OrderRepository;
import com.limitedgoodsreservation.product.entity.Product;
import com.limitedgoodsreservation.product.repository.ProductRepository;
import com.limitedgoodsreservation.purchase.dto.PurchaseResponse;
import com.limitedgoodsreservation.purchase.metrics.PurchaseMetrics;
import com.limitedgoodsreservation.stock.strategy.StockDeductionException;
import com.limitedgoodsreservation.stock.strategy.StockDeductionFailureReason;
import com.limitedgoodsreservation.stock.strategy.StockDeductionResult;
import com.limitedgoodsreservation.stock.strategy.StockDeductionStrategy;
import com.limitedgoodsreservation.stock.strategy.StockDeductionStrategyResolver;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseService {

    private final StockDeductionStrategyResolver stockDeductionStrategyResolver;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final PurchaseMetrics purchaseMetrics;

    public PurchaseService(
            StockDeductionStrategyResolver stockDeductionStrategyResolver,
            ProductRepository productRepository,
            OrderRepository orderRepository,
            PurchaseMetrics purchaseMetrics
    ) {
        this.stockDeductionStrategyResolver = stockDeductionStrategyResolver;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.purchaseMetrics = purchaseMetrics;
    }

    @PostConstruct
    void initializeMetrics() {
        purchaseMetrics.initialize(stockDeductionStrategyResolver.selectedStrategyName());
    }

    @Transactional
    public PurchaseResponse purchase(Long userId, Long productId) {
        if (userId == null) {
            throw new IllegalArgumentException("X-USER-ID is required.");
        }
        if (productId == null) {
            throw new IllegalArgumentException("productId is required.");
        }

        StockDeductionStrategy stockDeductionStrategy = stockDeductionStrategyResolver.selectedStrategy();
        String strategyName = stockDeductionStrategy.strategyName();
        purchaseMetrics.incrementAttempt(strategyName);

        try {
            StockDeductionResult deductionResult = purchaseMetrics.recordStockDecision(
                    strategyName,
                    () -> stockDeductionStrategy.deduct(productId)
            );
            Product product = productRepository.getReferenceById(deductionResult.productId());
            Order order = purchaseMetrics.recordOrderSave(
                    strategyName,
                    () -> orderRepository.save(Order.created(userId, product))
            );
            purchaseMetrics.incrementSuccess(strategyName);

            return PurchaseResponse.from(order);
        } catch (StockDeductionException exception) {
            purchaseMetrics.incrementFailure(strategyName, exception.reason());
            throw exception;
        } catch (RuntimeException exception) {
            purchaseMetrics.incrementFailure(strategyName, StockDeductionFailureReason.UNEXPECTED_FAILURE);
            throw exception;
        }
    }
}
