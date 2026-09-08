package com.limitedgoods.purchases;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api")
public class PurchaseController {
    private final PurchaseService purchases;
    private final OrderQueryService orders;
    public PurchaseController(PurchaseService purchases,OrderQueryService orders) { this.purchases=purchases; this.orders=orders; }
    @PostMapping("/purchases") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public OrderView purchase(@RequestHeader("X-User-Id") String user,@RequestHeader("Idempotency-Key") String key,
                              @Valid @RequestBody PurchaseRequest request) { return purchases.purchase(user,key,request); }
    @GetMapping("/orders/{id}")
    public OrderView get(@PathVariable UUID id,@RequestHeader("X-User-Id") String user) { return orders.get(id,user); }
}
