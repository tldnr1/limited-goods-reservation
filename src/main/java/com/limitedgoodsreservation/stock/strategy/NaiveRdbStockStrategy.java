package com.limitedgoodsreservation.stock.strategy;

import com.limitedgoodsreservation.product.entity.ProductStock;
import com.limitedgoodsreservation.product.repository.ProductStockRepository;
import org.springframework.stereotype.Component;

@Component
public class NaiveRdbStockStrategy implements StockDeductionStrategy {

    public static final String STRATEGY_NAME = "naive-rdb";

    private final ProductStockRepository productStockRepository;

    public NaiveRdbStockStrategy(ProductStockRepository productStockRepository) {
        this.productStockRepository = productStockRepository;
    }

    @Override
    public String strategyName() {
        return STRATEGY_NAME;
    }

    @Override
    public StockDeductionResult deduct(Long productId) {
        ProductStock stock = productStockRepository.findByProduct_Id(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found. productId=" + productId));

        if (!stock.hasAvailableQuantity()) {
            throw new SoldOutException(productId);
        }

        stock.increaseSoldQuantity();

        return new StockDeductionResult(productId);
    }
}
