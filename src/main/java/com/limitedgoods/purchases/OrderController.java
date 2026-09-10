package com.limitedgoods.purchases;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"all","payment"})
public class OrderController {
    private final OrderQueryService orders;
    public OrderController(OrderQueryService orders) { this.orders=orders; }
    @GetMapping("/api/orders/{id}")
    public OrderView get(@PathVariable UUID id,@RequestHeader("X-User-Id") String user) { return orders.get(id,user); }
}
