package com.limitedgoodsreservation.order.adapter.out.persistence;

import com.limitedgoodsreservation.order.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    long countByProduct_Id(Long productId);
}
