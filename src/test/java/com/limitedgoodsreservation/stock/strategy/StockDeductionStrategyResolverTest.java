package com.limitedgoodsreservation.stock.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class StockDeductionStrategyResolverTest {

    @Test
    void selectsConfiguredStrategy() {
        StockDeductionStrategy naive = new StubStockDeductionStrategy("naive-rdb");
        StockDeductionStrategy atomic = new StubStockDeductionStrategy("rdb-atomic");
        StockDeductionStrategyResolver resolver = new StockDeductionStrategyResolver(
                new StockStrategyProperties("rdb-atomic"),
                List.of(naive, atomic)
        );

        resolver.initialize();

        assertThat(resolver.selectedStrategy()).isSameAs(atomic);
        assertThat(resolver.selectedStrategyName()).isEqualTo("rdb-atomic");
    }

    @Test
    void defaultsToNaiveRdbStrategy() {
        StockDeductionStrategy naive = new StubStockDeductionStrategy("naive-rdb");
        StockDeductionStrategyResolver resolver = new StockDeductionStrategyResolver(
                new StockStrategyProperties(null),
                List.of(naive)
        );

        resolver.initialize();

        assertThat(resolver.selectedStrategy()).isSameAs(naive);
    }

    @Test
    void rejectsUnknownStrategy() {
        StockDeductionStrategyResolver resolver = new StockDeductionStrategyResolver(
                new StockStrategyProperties("missing"),
                List.of(new StubStockDeductionStrategy("naive-rdb"))
        );

        assertThatThrownBy(resolver::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown stock strategy: missing");
    }

    private record StubStockDeductionStrategy(String strategyName) implements StockDeductionStrategy {

        @Override
        public StockDeductionResult deduct(Long productId) {
            throw new UnsupportedOperationException();
        }
    }
}
