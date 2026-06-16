package com.limitedgoodsreservation.product.repository;

import com.limitedgoodsreservation.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
