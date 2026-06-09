package com.limitedgoodsreservation.purchase.application;

import com.limitedgoodsreservation.order.adapter.out.persistence.OrderRepository;
import com.limitedgoodsreservation.order.domain.Order;
import com.limitedgoodsreservation.purchase.adapter.in.web.PurchaseResponse;
import com.limitedgoodsreservation.stock.application.SoldOutException;
import com.limitedgoodsreservation.stock.application.port.StockDeductionPort;
import com.limitedgoodsreservation.stock.application.port.StockDeductionResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseService {

    private final StockDeductionPort stockDeductionPort;
    private final OrderRepository orderRepository;
    private final PurchaseMetrics purchaseMetrics;

    public PurchaseService(
            StockDeductionPort stockDeductionPort,
            OrderRepository orderRepository,
            PurchaseMetrics purchaseMetrics
    ) {
        this.stockDeductionPort = stockDeductionPort;
        this.orderRepository = orderRepository;
        this.purchaseMetrics = purchaseMetrics;
        this.purchaseMetrics.initialize(stockDeductionPort.strategyName());
    }

    @Transactional
    public PurchaseResponse purchase(Long userId, Long productId) {
        if (userId == null) {
            throw new IllegalArgumentException("X-USER-ID is required.");
        }
        if (productId == null) {
            throw new IllegalArgumentException("productId is required.");
        }

        String strategyName = stockDeductionPort.strategyName();
        purchaseMetrics.incrementAttempt(strategyName);

        try {
            StockDeductionResult deductionResult = stockDeductionPort.deduct(productId);
            Order order = orderRepository.save(Order.created(userId, deductionResult.product()));
            purchaseMetrics.incrementSuccess(strategyName);

            return PurchaseResponse.from(order);
        } catch (SoldOutException exception) {
            purchaseMetrics.incrementSoldOut(strategyName);
            throw exception;
        } catch (RuntimeException exception) {
            purchaseMetrics.incrementUnexpectedFailure(strategyName);
            throw exception;
        }
    }
}
