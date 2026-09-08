package com.limitedgoods.sales;
import java.util.*;
import jakarta.persistence.*;
import org.springframework.stereotype.Repository;
@Repository
public class SaleRepository {
    @PersistenceContext private EntityManager em;
    public void save(Sale sale) { em.persist(sale); }
    public void save(SaleItem item) { em.persist(item); }
    public Sale find(UUID id) { return em.find(Sale.class,id); }
    public List<SaleItem> items(UUID id) {
        return em.createQuery("from SaleItem where saleId=:id order by id",SaleItem.class).setParameter("id",id).getResultList();
    }
    // Always lock inventory in UUID order, including confirm/expiry paths.
    public List<SaleItem> lockItems(Collection<UUID> ids) {
        return em.createQuery("from SaleItem where id in :ids order by id",SaleItem.class)
            .setParameter("ids",ids).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
    }
}
