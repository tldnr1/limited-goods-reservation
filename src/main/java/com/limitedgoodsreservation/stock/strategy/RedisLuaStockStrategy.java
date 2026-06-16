package com.limitedgoodsreservation.stock.strategy;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisLuaStockStrategy implements StockDeductionStrategy {

    public static final String STRATEGY_NAME = "redis-lua";

    private static final Long SOLD_OUT = -1L;
    private static final Long MISSING_STOCK_KEY = -2L;
    private static final DefaultRedisScript<Long> DEDUCT_SCRIPT = new DefaultRedisScript<>("""
            local available = redis.call('GET', KEYS[1])
            if not available then
                return -2
            end
            available = tonumber(available)
            if available <= 0 then
                return -1
            end
            return redis.call('DECR', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisLuaStockStrategy(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String strategyName() {
        return STRATEGY_NAME;
    }

    @Override
    public StockDeductionResult deduct(Long productId) {
        Long result = redisTemplate.execute(DEDUCT_SCRIPT, List.of(stockKey(productId)));
        if (result == null || result.equals(MISSING_STOCK_KEY)) {
            throw new StockDeductionException(
                    StockDeductionFailureReason.UNEXPECTED_FAILURE,
                    "Redis stock key missing. productId=" + productId
            );
        }
        if (result.equals(SOLD_OUT)) {
            throw new SoldOutException(productId);
        }

        return new StockDeductionResult(productId);
    }

    public static String stockKey(Long productId) {
        return "stock:available:" + productId;
    }
}
