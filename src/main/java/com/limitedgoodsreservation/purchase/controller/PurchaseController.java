package com.limitedgoodsreservation.purchase.controller;

import com.limitedgoodsreservation.purchase.dto.PurchaseRequest;
import com.limitedgoodsreservation.purchase.dto.PurchaseResult;
import com.limitedgoodsreservation.purchase.dto.PurchaseResponse;
import com.limitedgoodsreservation.purchase.service.PurchaseService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/purchases")
public class PurchaseController {

    private final PurchaseService purchaseService;

    public PurchaseController(PurchaseService purchaseService) {
        this.purchaseService = purchaseService;
    }

    @PostMapping
    public ResponseEntity<PurchaseResponse> purchase(
            @RequestHeader("X-USER-ID") Long userId,
            @RequestHeader(value = "X-RUN-ID", required = false) String runId,
            @RequestHeader(value = "X-IDEMPOTENCY-KEY", required = false) String idempotencyKey,
            @RequestBody PurchaseRequest request
    ) {
        PurchaseResult result = purchaseService.purchase(userId, request.productId(), runId, idempotencyKey);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }
}
