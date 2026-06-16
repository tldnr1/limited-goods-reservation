package com.limitedgoodsreservation.stock.strategy;

import com.limitedgoodsreservation.product.entity.ProductStock;
import com.limitedgoodsreservation.product.repository.ProductStockRepository;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Component
public class RdbPessimisticStockStrategy implements StockDeductionStrategy {

    public static final String STRATEGY_NAME = "rdb-pessimistic";

    private final ProductStockRepository productStockRepository;

    public RdbPessimisticStockStrategy(ProductStockRepository productStockRepository) {
        this.productStockRepository = productStockRepository;
    }

    @Override
    public String strategyName() {
        return STRATEGY_NAME;
    }

    @Override
    public StockDeductionResult deduct(Long productId) {
        try {
            ProductStock stock = productStockRepository.findWithPessimisticWriteLockByProductId(productId)
                    .orElseThrow(() -> new IllegalArgumentException("Product not found. productId=" + productId));

            if (!stock.hasAvailableQuantity()) {
                throw new SoldOutException(productId);
            }

            stock.increaseSoldQuantity();

            return new StockDeductionResult(productId);
        } catch (PessimisticLockingFailureException exception) {
            throw new StockDeductionException(
                    StockDeductionFailureReason.LOCK_TIMEOUT,
                    "Failed to acquire stock lock. productId=" + productId
            );
        }
    }
}
