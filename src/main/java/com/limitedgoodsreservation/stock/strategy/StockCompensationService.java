package com.limitedgoodsreservation.stock.strategy;

import org.springframework.stereotype.Service;

@Service
public class StockCompensationService {

    private final RedisLuaStockStrategy redisLuaStockStrategy;

    public StockCompensationService(RedisLuaStockStrategy redisLuaStockStrategy) {
        this.redisLuaStockStrategy = redisLuaStockStrategy;
    }

    public boolean compensate(String strategyName, Long productId) {
        if (!RedisLuaStockStrategy.STRATEGY_NAME.equals(strategyName)) {
            return true;
        }

        try {
            redisLuaStockStrategy.compensate(productId);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
