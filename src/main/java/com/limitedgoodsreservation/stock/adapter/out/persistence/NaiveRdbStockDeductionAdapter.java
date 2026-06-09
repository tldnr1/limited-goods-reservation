package com.limitedgoodsreservation.stock.adapter.out.persistence;

import com.limitedgoodsreservation.stock.application.SoldOutException;
import com.limitedgoodsreservation.stock.application.port.StockDeductionPort;
import com.limitedgoodsreservation.stock.application.port.StockDeductionResult;
import com.limitedgoodsreservation.stock.domain.ProductStock;
import org.springframework.stereotype.Component;

@Component
public class NaiveRdbStockDeductionAdapter implements StockDeductionPort {

    public static final String STRATEGY_NAME = "naive-rdb";

    private final ProductStockRepository productStockRepository;

    public NaiveRdbStockDeductionAdapter(ProductStockRepository productStockRepository) {
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

        return new StockDeductionResult(stock.getProduct());
    }
}
