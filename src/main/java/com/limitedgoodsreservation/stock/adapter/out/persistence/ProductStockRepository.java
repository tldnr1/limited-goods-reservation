package com.limitedgoodsreservation.stock.adapter.out.persistence;

import com.limitedgoodsreservation.stock.domain.ProductStock;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductStockRepository extends JpaRepository<ProductStock, Long> {

    Optional<ProductStock> findByProduct_Id(Long productId);
}
