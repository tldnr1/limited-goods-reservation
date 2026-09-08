package com.limitedgoods.reservations;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="reservations")
public class Reservation {
    @Id public UUID orderId;
    public String status;
    public Instant holdExpiresAt;
    public Instant confirmationDeadline;
    protected Reservation() {}
    public Reservation(UUID order, Instant expires) { orderId=order; holdExpiresAt=expires; status="ACTIVE"; }
}
