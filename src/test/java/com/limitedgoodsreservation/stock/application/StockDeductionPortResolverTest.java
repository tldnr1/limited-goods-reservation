package com.limitedgoodsreservation.stock.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.limitedgoodsreservation.stock.application.port.StockDeductionPort;
import com.limitedgoodsreservation.stock.application.port.StockDeductionResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class StockDeductionPortResolverTest {

    @Test
    void selectsConfiguredStrategy() {
        StockDeductionPort naive = new StubStockDeductionPort("naive-rdb");
        StockDeductionPort atomic = new StubStockDeductionPort("rdb-atomic");
        StockDeductionPortResolver resolver = new StockDeductionPortResolver(
                new StockStrategyProperties("rdb-atomic"),
                List.of(naive, atomic)
        );

        resolver.initialize();

        assertThat(resolver.selectedPort()).isSameAs(atomic);
        assertThat(resolver.selectedStrategyName()).isEqualTo("rdb-atomic");
    }

    @Test
    void defaultsToNaiveRdbStrategy() {
        StockDeductionPort naive = new StubStockDeductionPort("naive-rdb");
        StockDeductionPortResolver resolver = new StockDeductionPortResolver(
                new StockStrategyProperties(null),
                List.of(naive)
        );

        resolver.initialize();

        assertThat(resolver.selectedPort()).isSameAs(naive);
    }

    @Test
    void rejectsUnknownStrategy() {
        StockDeductionPortResolver resolver = new StockDeductionPortResolver(
                new StockStrategyProperties("missing"),
                List.of(new StubStockDeductionPort("naive-rdb"))
        );

        assertThatThrownBy(resolver::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown stock strategy: missing");
    }

    private record StubStockDeductionPort(String strategyName) implements StockDeductionPort {

        @Override
        public StockDeductionResult deduct(Long productId) {
            throw new UnsupportedOperationException();
        }
    }
}
