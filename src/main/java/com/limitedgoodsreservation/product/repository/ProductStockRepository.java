package com.limitedgoodsreservation.product.repository;

import com.limitedgoodsreservation.product.entity.ProductStock;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductStockRepository extends JpaRepository<ProductStock, Long> {

    Optional<ProductStock> findByProduct_Id(Long productId);
}
