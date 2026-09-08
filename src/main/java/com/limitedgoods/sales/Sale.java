package com.limitedgoods.sales;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="sales")
public class Sale {
    @Id public UUID id;
    @Column(nullable=false) public String name;
    @Column(nullable=false) public Instant opensAt;
    protected Sale() {}
    public Sale(String name, Instant opensAt) { this.id=UUID.randomUUID(); this.name=name; this.opensAt=opensAt; }
}
