package com.limitedgoods.purchases;
import jakarta.persistence.*;
import org.springframework.stereotype.Repository;
import java.util.*;
@Repository
public class OrderRepository {
    @PersistenceContext private EntityManager em;
    public void serializeKey(String user,String key) {
        // Transaction-scoped advisory lock; hash collisions only cause extra serialization.
        em.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:key,0))")
            .setParameter("key",user.length()+":"+user+key).getSingleResult();
    }
    public Order byKey(String user,String key) {
        return em.createQuery("from Order where userId=:user and idempotencyKey=:key",Order.class)
            .setParameter("user",user).setParameter("key",key).getResultStream().findFirst().orElse(null);
    }
    public Order find(UUID id) { return em.find(Order.class,id); }
    public Order lock(UUID id) { return em.find(Order.class,id,LockModeType.PESSIMISTIC_WRITE); }
    public void save(Order order) { em.persist(order); }
    public void save(OrderItem item) { em.persist(item); }
    public List<OrderItem> items(UUID order) {
        return em.createQuery("from OrderItem where orderId=:id order by saleItemId",OrderItem.class)
            .setParameter("id",order).getResultList();
    }
    public long used(String user,UUID item) {
        return em.createQuery("""
            select coalesce(sum(i.quantity),0) from OrderItem i, Order o
            where i.orderId=o.id and i.saleItemId=:item and o.userId=:user and o.status<>'EXPIRED'
            """,Long.class).setParameter("item",item).setParameter("user",user).getSingleResult();
    }
}
