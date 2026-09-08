package com.limitedgoods.reservations;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;
@Repository
public class ReservationRepository {
    @PersistenceContext private EntityManager em;
    public Reservation find(UUID id) { return em.find(Reservation.class,id); }
    public void save(Reservation reservation) { em.persist(reservation); }
    public List<UUID> due(Instant now) {
        return em.createQuery("""
            select r.orderId from Reservation r, Order o where r.orderId=o.id
            and r.status='ACTIVE' and o.status='PAYMENT_PENDING' and r.holdExpiresAt<=:now
            order by r.holdExpiresAt
            """,UUID.class).setParameter("now",now).setMaxResults(100).getResultList();
    }
}
