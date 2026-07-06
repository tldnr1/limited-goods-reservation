package com.limitedgoodsreservation.waitingroom.controller;

import com.limitedgoodsreservation.waitingroom.dto.WaitingRoomEnterRequest;
import com.limitedgoodsreservation.waitingroom.dto.WaitingRoomStatusResponse;
import com.limitedgoodsreservation.waitingroom.service.WaitingRoomEntry;
import com.limitedgoodsreservation.waitingroom.service.WaitingRoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v3/waiting-room")
public class WaitingRoomController {

    private final WaitingRoomService waitingRoomService;

    public WaitingRoomController(WaitingRoomService waitingRoomService) {
        this.waitingRoomService = waitingRoomService;
    }

    @PostMapping("/enter")
    public ResponseEntity<WaitingRoomStatusResponse> enter(
            @RequestHeader("X-USER-ID") Long userId,
            @RequestBody WaitingRoomEnterRequest request
    ) {
        WaitingRoomEntry entry = waitingRoomService.enter(userId, request.productId());
        return ResponseEntity.ok(WaitingRoomStatusResponse.from(entry, waitingRoomService.retryAfterSeconds()));
    }

    @GetMapping("/status")
    public ResponseEntity<WaitingRoomStatusResponse> status(
            @RequestHeader("X-USER-ID") Long userId,
            @RequestParam Long productId
    ) {
        WaitingRoomEntry entry = waitingRoomService.status(userId, productId);
        return ResponseEntity.ok(WaitingRoomStatusResponse.from(entry, waitingRoomService.retryAfterSeconds()));
    }
}
