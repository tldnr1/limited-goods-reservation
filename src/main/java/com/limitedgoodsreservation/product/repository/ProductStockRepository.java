package com.limitedgoodsreservation.product.repository;

import com.limitedgoodsreservation.product.entity.ProductStock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductStockRepository extends JpaRepository<ProductStock, Long> {

    Optional<ProductStock> findByProduct_Id(Long productId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ProductStock stock
            set stock.soldQuantity = stock.soldQuantity + 1
            where stock.product.id = :productId
              and stock.soldQuantity < stock.initialQuantity
            """)
    int increaseSoldQuantityIfAvailable(@Param("productId") Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select stock
            from ProductStock stock
            join fetch stock.product
            where stock.product.id = :productId
            """)
    Optional<ProductStock> findWithPessimisticWriteLockByProductId(@Param("productId") Long productId);
}
