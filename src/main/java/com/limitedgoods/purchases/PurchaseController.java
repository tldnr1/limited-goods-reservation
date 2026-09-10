package com.limitedgoods.purchases;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api") @org.springframework.context.annotation.Profile({"all","reservation"})
public class PurchaseController {
    private final PurchaseService purchases;
    public PurchaseController(PurchaseService purchases) { this.purchases=purchases; }
    @PostMapping("/purchases") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public OrderView purchase(@RequestHeader("X-User-Id") String user,@RequestHeader("Idempotency-Key") String key,
                              @RequestHeader(value="X-Admission-Ticket",required=false) String ticket,
                              @Valid @RequestBody PurchaseRequest request) { return purchases.purchase(user,key,request,ticket); }
}
