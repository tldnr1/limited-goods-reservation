package com.limitedgoodsreservation.reservation.repository;

import com.limitedgoodsreservation.reservation.entity.Reservation;
import com.limitedgoodsreservation.reservation.entity.ReservationStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    Optional<Reservation> findByProduct_IdAndUserId(Long productId, Long userId);

    long countByProduct_IdAndStatus(Long productId, ReservationStatus status);
}
