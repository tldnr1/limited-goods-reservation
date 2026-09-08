package com.limitedgoods.purchases;
import java.time.Instant;
import java.util.*;
public record OrderView(UUID id, String status, long totalAmount, Instant holdExpiresAt,
                        List<Item> items, List<Payment> payments) {
    public record Item(UUID saleItemId,int quantity,long unitPrice) {}
    public record Payment(UUID id,String status,String scenario) {}
}
