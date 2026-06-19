package com.limitedgoodsreservation.order.repository;

import com.limitedgoodsreservation.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    long countByProduct_Id(Long productId);
}
