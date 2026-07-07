package com.limitedgoodsreservation.stock.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.limitedgoodsreservation.product.entity.ProductStock;
import com.limitedgoodsreservation.product.repository.ProductStockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:rdb-atomic-strategy;MODE=PostgreSQL;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.sql.init.mode=always",
        "waiting-room.admission.scheduler-enabled=false"
})
@Transactional
class RdbAtomicStockStrategyTest {

    @Autowired
    private RdbAtomicStockStrategy strategy;

    @Autowired
    private ProductStockRepository productStockRepository;

    @Test
    void increasesSoldQuantityOnlyWhenStockIsAvailable() {
        for (int i = 0; i < 100; i++) {
            strategy.deduct(1L);
        }

        assertThatThrownBy(() -> strategy.deduct(1L))
                .isInstanceOf(SoldOutException.class);

        ProductStock stock = productStockRepository.findByProduct_Id(1L).orElseThrow();
        assertThat(stock.getSoldQuantity()).isEqualTo(100);
    }
}
