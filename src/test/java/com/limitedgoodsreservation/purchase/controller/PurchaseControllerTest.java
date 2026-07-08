package com.limitedgoodsreservation.purchase.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.limitedgoodsreservation.global.GlobalExceptionHandler;
import com.limitedgoodsreservation.purchase.dto.PurchaseResult;
import com.limitedgoodsreservation.purchase.dto.PurchaseResponse;
import com.limitedgoodsreservation.purchase.service.PurchaseService;
import com.limitedgoodsreservation.waitingroom.service.ActiveTokenRequiredException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PurchaseControllerTest {

    private final PurchaseService purchaseService = Mockito.mock(PurchaseService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new PurchaseController(purchaseService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void rejectsPurchaseWhenActiveTokenIsMissing() throws Exception {
        doThrow(new ActiveTokenRequiredException(1L, 1001L))
                .when(purchaseService).purchase(1001L, 1L, null, "request-1");

        mockMvc.perform(post("/api/v1/purchases")
                        .header("X-USER-ID", "1001")
                        .header("X-IDEMPOTENCY-KEY", "request-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_TOKEN_REQUIRED"));

        verify(purchaseService).purchase(1001L, 1L, null, "request-1");
    }

    @Test
    void returnsCreatedWhenReservationIsNew() throws Exception {
        when(purchaseService.purchase(1001L, 1L, null, "request-1"))
                .thenReturn(new PurchaseResult(new PurchaseResponse(1L, 1001L, 1L, "RESERVED"), true));

        mockMvc.perform(post("/api/v1/purchases")
                        .header("X-USER-ID", "1001")
                        .header("X-IDEMPOTENCY-KEY", "request-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESERVED"));

        verify(purchaseService).purchase(1001L, 1L, null, "request-1");
    }

    @Test
    void returnsOkWhenIdempotencyKeyReusesExistingReservation() throws Exception {
        when(purchaseService.purchase(1001L, 1L, null, "request-1"))
                .thenReturn(new PurchaseResult(new PurchaseResponse(1L, 1001L, 1L, "RESERVED"), false));

        mockMvc.perform(post("/api/v1/purchases")
                        .header("X-USER-ID", "1001")
                        .header("X-IDEMPOTENCY-KEY", "request-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(1L));
    }
}
