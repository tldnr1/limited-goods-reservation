package com.limitedgoodsreservation.purchase.controller;

import com.limitedgoodsreservation.purchase.dto.PurchaseRequest;
import com.limitedgoodsreservation.purchase.dto.PurchaseResponse;
import com.limitedgoodsreservation.purchase.service.PurchaseService;
import com.limitedgoodsreservation.waitingroom.service.WaitingRoomService;
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
    private final WaitingRoomService waitingRoomService;

    public PurchaseController(PurchaseService purchaseService, WaitingRoomService waitingRoomService) {
        this.purchaseService = purchaseService;
        this.waitingRoomService = waitingRoomService;
    }

    @PostMapping
    public ResponseEntity<PurchaseResponse> purchase(
            @RequestHeader("X-USER-ID") Long userId,
            @RequestHeader(value = "X-RUN-ID", required = false) String runId,
            @RequestBody PurchaseRequest request
    ) {
        waitingRoomService.consumeActiveTokenOrThrow(userId, request.productId());
        PurchaseResponse response = purchaseService.purchase(userId, request.productId(), runId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
