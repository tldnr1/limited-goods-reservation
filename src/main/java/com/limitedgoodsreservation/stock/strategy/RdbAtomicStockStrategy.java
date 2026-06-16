package com.limitedgoodsreservation.stock.strategy;

import com.limitedgoodsreservation.product.repository.ProductStockRepository;
import org.springframework.stereotype.Component;

@Component
public class RdbAtomicStockStrategy implements StockDeductionStrategy {

    public static final String STRATEGY_NAME = "rdb-atomic";

    private final ProductStockRepository productStockRepository;

    public RdbAtomicStockStrategy(ProductStockRepository productStockRepository) {
        this.productStockRepository = productStockRepository;
    }

    @Override
    public String strategyName() {
        return STRATEGY_NAME;
    }

    @Override
    public StockDeductionResult deduct(Long productId) {
        int updatedCount = productStockRepository.increaseSoldQuantityIfAvailable(productId);
        if (updatedCount == 0) {
            throw new SoldOutException(productId);
        }

        return new StockDeductionResult(productId);
    }
}
