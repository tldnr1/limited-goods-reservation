package com.limitedgoodsreservation.stock.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisLuaStockStrategyTest {

    @Test
    void succeedsWhenRedisStockCanBeDecremented() {
        StubRedisTemplate redisTemplate = new StubRedisTemplate(99L);
        RedisLuaStockStrategy strategy = new RedisLuaStockStrategy(redisTemplate);

        StockDeductionResult result = strategy.deduct(1L);

        assertThat(result.productId()).isEqualTo(1L);
        assertThat(redisTemplate.lastKey).isEqualTo("stock:available:1");
    }

    @Test
    void rejectsWhenRedisStockIsSoldOut() {
        RedisLuaStockStrategy strategy = new RedisLuaStockStrategy(new StubRedisTemplate(-1L));

        assertThatThrownBy(() -> strategy.deduct(1L))
                .isInstanceOf(SoldOutException.class);
    }

    @Test
    void treatsMissingRedisStockKeyAsSetupFailure() {
        RedisLuaStockStrategy strategy = new RedisLuaStockStrategy(new StubRedisTemplate(-2L));

        assertThatThrownBy(() -> strategy.deduct(1L))
                .isInstanceOf(StockDeductionException.class)
                .hasMessageContaining("Redis stock key missing");
    }

    @Test
    void compensatesRedisStockByProductKey() {
        StubRedisTemplate redisTemplate = new StubRedisTemplate(100L);
        RedisLuaStockStrategy strategy = new RedisLuaStockStrategy(redisTemplate);

        strategy.compensate(1L);

        assertThat(redisTemplate.lastKey).isEqualTo("stock:available:1");
    }

    private static class StubRedisTemplate extends StringRedisTemplate {

        private final Long result;
        private String lastKey;

        private StubRedisTemplate(Long result) {
            this.result = result;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            lastKey = keys.get(0);
            return (T) result;
        }
    }
}
