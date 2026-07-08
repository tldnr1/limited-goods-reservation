package com.limitedgoodsreservation.reservation.entity;

import com.limitedgoodsreservation.product.entity.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(
        name = "reservations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_reservations_product_user",
                        columnNames = {"product_id", "user_id"}
                ),
                @UniqueConstraint(
                        name = "uk_reservations_idempotency_key",
                        columnNames = "idempotency_key"
                )
        }
)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReservationStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Reservation() {
    }

    private Reservation(Long userId, Product product, String idempotencyKey, ReservationStatus status) {
        this.userId = userId;
        this.product = product;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
    }

    public static Reservation reserved(Long userId, Product product, String idempotencyKey) {
        return new Reservation(userId, product, idempotencyKey, ReservationStatus.RESERVED);
    }

    public boolean matches(Long expectedUserId, Long expectedProductId) {
        return userId.equals(expectedUserId) && getProductId().equals(expectedProductId);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getProductId() {
        return product.getId();
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
