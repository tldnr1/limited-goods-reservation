package com.limitedgoods.payments;
import jakarta.persistence.*;
import java.util.*;
import org.springframework.stereotype.Repository;
@Repository
public class PaymentRepository {
    @PersistenceContext private EntityManager em;
    public PaymentAttempt find(UUID id) { return em.find(PaymentAttempt.class,id); }
    public void save(PaymentAttempt attempt) { em.persist(attempt); }
    public List<PaymentAttempt> forOrder(UUID id) {
        return em.createQuery("from PaymentAttempt where orderId=:id order by createdAt,id",PaymentAttempt.class)
            .setParameter("id",id).getResultList();
    }
    public UUID orderId(UUID attempt) {
        return em.createQuery("select p.orderId from PaymentAttempt p where p.id=:id",UUID.class)
            .setParameter("id",attempt).getResultStream().findFirst().orElse(null);
    }
}
