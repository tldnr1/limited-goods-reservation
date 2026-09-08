package com.limitedgoods.purchases;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="orders")
public class Order {
    @Id public UUID id;
    public String userId;
    public UUID saleId;
    public String idempotencyKey;
    @Column(length=1000) public String fingerprint;
    public String status;
    public long totalAmount;
    public Instant createdAt;
    protected Order() {}
    public Order(String user, UUID sale, String key, String fingerprint, Instant now) {
        id=UUID.randomUUID(); userId=user; saleId=sale; idempotencyKey=key;
        this.fingerprint=fingerprint; createdAt=now; status="PAYMENT_PENDING";
    }
}
