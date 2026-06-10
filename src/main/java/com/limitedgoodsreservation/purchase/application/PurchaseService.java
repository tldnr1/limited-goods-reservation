package com.limitedgoodsreservation.purchase.application;

import com.limitedgoodsreservation.order.adapter.out.persistence.OrderRepository;
import com.limitedgoodsreservation.order.domain.Order;
import com.limitedgoodsreservation.purchase.adapter.in.web.PurchaseResponse;
import com.limitedgoodsreservation.stock.application.StockDeductionException;
import com.limitedgoodsreservation.stock.application.StockDeductionFailureReason;
import com.limitedgoodsreservation.stock.application.StockDeductionPortResolver;
import com.limitedgoodsreservation.stock.application.port.StockDeductionPort;
import com.limitedgoodsreservation.stock.application.port.StockDeductionResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseService {

    private final StockDeductionPortResolver stockDeductionPortResolver;
    private final OrderRepository orderRepository;
    private final PurchaseMetrics purchaseMetrics;

    public PurchaseService(
            StockDeductionPortResolver stockDeductionPortResolver,
            OrderRepository orderRepository,
            PurchaseMetrics purchaseMetrics
    ) {
        this.stockDeductionPortResolver = stockDeductionPortResolver;
        this.orderRepository = orderRepository;
        this.purchaseMetrics = purchaseMetrics;
        this.purchaseMetrics.initialize(stockDeductionPortResolver.selectedStrategyName());
    }

    @Transactional
    public PurchaseResponse purchase(Long userId, Long productId) {
        if (userId == null) {
            throw new IllegalArgumentException("X-USER-ID is required.");
        }
        if (productId == null) {
            throw new IllegalArgumentException("productId is required.");
        }

        StockDeductionPort stockDeductionPort = stockDeductionPortResolver.selectedPort();
        String strategyName = stockDeductionPort.strategyName();
        purchaseMetrics.incrementAttempt(strategyName);

        try {
            StockDeductionResult deductionResult = stockDeductionPort.deduct(productId);
            Order order = orderRepository.save(Order.created(userId, deductionResult.product()));
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
