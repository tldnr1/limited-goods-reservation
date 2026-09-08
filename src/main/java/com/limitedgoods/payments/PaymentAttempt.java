package com.limitedgoods.payments;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="payment_attempts")
public class PaymentAttempt {
    @Id public UUID id;
    public UUID orderId;
    public String idempotencyKey;
    public String scenario;
    public String status;
    public long amount;
    public Instant createdAt;
    public Instant nextCheckAt;
    public Instant leaseUntil;
    protected PaymentAttempt() {}
    public PaymentAttempt(UUID order,String key,String scenario,long amount,Instant now) {
        id=UUID.randomUUID(); orderId=order; idempotencyKey=key; this.scenario=scenario;
        this.amount=amount; status="CREATED"; createdAt=now; nextCheckAt=now;
    }
    public boolean terminal() { return status.equals("SUCCEEDED") || status.equals("FAILED"); }
}
