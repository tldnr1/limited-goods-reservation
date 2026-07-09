package com.limitedgoodsreservation.purchase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.limitedgoodsreservation.product.entity.ProductStock;
import com.limitedgoodsreservation.product.repository.ProductStockRepository;
import com.limitedgoodsreservation.purchase.dto.PurchaseResult;
import com.limitedgoodsreservation.reservation.exception.AlreadyReservedException;
import com.limitedgoodsreservation.reservation.repository.ReservationRepository;
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
        "spring.sql.init.mode=always",
        "purchase.architecture=rdb-atomic",
        "waiting-room.enabled=false",
        "waiting-room.admission.scheduler-enabled=false"
})
@Transactional
class PurchaseServiceTest {

    @Autowired
    private PurchaseService purchaseService;

    @Autowired
    private ProductStockRepository productStockRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Test
    void createsReservationAndIncreasesSoldQuantity() {
        PurchaseResult result = purchaseService.purchase(1001L, 1L, null, "request-1");

        ProductStock stock = productStockRepository.findByProduct_Id(1L).orElseThrow();
        assertThat(result.created()).isTrue();
        assertThat(result.response().userId()).isEqualTo(1001L);
        assertThat(result.response().productId()).isEqualTo(1L);
        assertThat(result.response().status()).isEqualTo("RESERVED");
        assertThat(stock.getSoldQuantity()).isEqualTo(1);
        assertThat(reservationRepository.count()).isEqualTo(1);
    }

    @Test
    void returnsExistingReservationForSameIdempotencyKey() {
        PurchaseResult first = purchaseService.purchase(1001L, 1L, null, "request-1");
        PurchaseResult second = purchaseService.purchase(1001L, 1L, null, "request-1");

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.response().reservationId()).isEqualTo(first.response().reservationId());
        assertThat(reservationRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsDifferentIdempotencyKeyWhenUserAlreadyReservedProduct() {
        purchaseService.purchase(1001L, 1L, null, "request-1");

        assertThatThrownBy(() -> purchaseService.purchase(1001L, 1L, null, "request-2"))
                .isInstanceOf(AlreadyReservedException.class);
        assertThat(reservationRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsPurchaseWhenStockIsSoldOut() {
        ProductStock stock = productStockRepository.findByProduct_Id(1L).orElseThrow();
        for (int i = 0; i < stock.getInitialQuantity(); i++) {
            stock.increaseSoldQuantity();
        }

        assertThatThrownBy(() -> purchaseService.purchase(1001L, 1L, null, "request-1"))
                .isInstanceOf(SoldOutException.class);
    }

    @Test
    void requiresIdempotencyKey() {
        assertThatThrownBy(() -> purchaseService.purchase(1001L, 1L, null, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("X-IDEMPOTENCY-KEY");
    }
}
