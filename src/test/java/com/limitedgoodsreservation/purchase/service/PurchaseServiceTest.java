package com.limitedgoodsreservation.purchase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.limitedgoodsreservation.order.repository.OrderRepository;
import com.limitedgoodsreservation.product.entity.ProductStock;
import com.limitedgoodsreservation.product.repository.ProductStockRepository;
import com.limitedgoodsreservation.purchase.dto.PurchaseResponse;
import com.limitedgoodsreservation.stock.strategy.SoldOutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:purchase-service;MODE=PostgreSQL;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.sql.init.mode=always"
})
@Transactional
class PurchaseServiceTest {

    @Autowired
    private PurchaseService purchaseService;

    @Autowired
    private ProductStockRepository productStockRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void createsOrderAndIncreasesSoldQuantity() {
        PurchaseResponse response = purchaseService.purchase(1001L, 1L);

        ProductStock stock = productStockRepository.findByProduct_Id(1L).orElseThrow();
        assertThat(response.userId()).isEqualTo(1001L);
        assertThat(response.productId()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo("CREATED");
        assertThat(stock.getSoldQuantity()).isEqualTo(1);
        assertThat(orderRepository.countByProduct_Id(1L)).isEqualTo(1);
    }

    @Test
    void rejectsPurchaseWhenStockIsSoldOut() {
        ProductStock stock = productStockRepository.findByProduct_Id(1L).orElseThrow();
        for (int i = 0; i < stock.getInitialQuantity(); i++) {
            stock.increaseSoldQuantity();
        }

        assertThatThrownBy(() -> purchaseService.purchase(1001L, 1L))
                .isInstanceOf(SoldOutException.class);
    }
}
