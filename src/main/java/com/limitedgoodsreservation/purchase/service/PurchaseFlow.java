package com.limitedgoodsreservation.purchase.service;

import com.limitedgoodsreservation.purchase.dto.PurchaseResult;

public interface PurchaseFlow {

    boolean supports(String architecture);

    String metricsName();

    PurchaseResult purchase(Long userId, Long productId, String runId, String idempotencyKey);
}
