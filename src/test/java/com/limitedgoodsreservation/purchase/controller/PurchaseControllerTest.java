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
import com.limitedgoodsreservation.purchase.dto.PurchaseResponse;
import com.limitedgoodsreservation.purchase.service.PurchaseService;
import com.limitedgoodsreservation.waitingroom.service.ActiveTokenRequiredException;
import com.limitedgoodsreservation.waitingroom.service.WaitingRoomService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PurchaseControllerTest {

    private final PurchaseService purchaseService = Mockito.mock(PurchaseService.class);
    private final WaitingRoomService waitingRoomService = Mockito.mock(WaitingRoomService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new PurchaseController(purchaseService, waitingRoomService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void rejectsPurchaseWhenActiveTokenIsMissing() throws Exception {
        doThrow(new ActiveTokenRequiredException(1L, 1001L))
                .when(waitingRoomService).consumeActiveTokenOrThrow(1001L, 1L);

        mockMvc.perform(post("/api/v1/purchases")
                        .header("X-USER-ID", "1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_TOKEN_REQUIRED"));

        verify(purchaseService, never()).purchase(eq(1001L), eq(1L), Mockito.any());
    }

    @Test
    void consumesActiveTokenBeforePurchase() throws Exception {
        when(purchaseService.purchase(1001L, 1L, null))
                .thenReturn(new PurchaseResponse(1L, 1001L, 1L, "CREATED"));

        mockMvc.perform(post("/api/v1/purchases")
                        .header("X-USER-ID", "1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"));

        verify(waitingRoomService).consumeActiveTokenOrThrow(1001L, 1L);
    }
}
