package com.limitedgoods.sales;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/sales") @org.springframework.context.annotation.Profile({"all","reservation"})
public class SaleController {
    private final SaleService service;
    public SaleController(SaleService service) { this.service=service; }
    // Local fixture/admin endpoint. Do not expose publicly as production authorization.
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public SaleService.View create(@Valid @RequestBody SaleService.Create request) { return service.create(request); }
    @GetMapping("/{id}") public SaleService.View get(@PathVariable UUID id) { return service.get(id); }
}
