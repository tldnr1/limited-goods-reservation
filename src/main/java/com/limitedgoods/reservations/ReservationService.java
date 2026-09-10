package com.limitedgoods.reservations;
import com.limitedgoods.purchases.*;
import com.limitedgoods.sales.*;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class ReservationService {
    private final ReservationRepository reservations;
    private final OrderRepository orders;
    private final SaleRepository sales;
    private final Clock clock;
    private final long holdSeconds;
    public ReservationService(ReservationRepository reservations,OrderRepository orders,SaleRepository sales,
                              Clock clock,@Value("${app.hold-seconds}") long holdSeconds) {
        this.reservations=reservations; this.orders=orders; this.sales=sales; this.clock=clock; this.holdSeconds=holdSeconds;
    }
    public Reservation create(Order order) {
        var hold=new Reservation(order.id,order.createdAt.plusSeconds(holdSeconds));
        reservations.save(hold);
        return hold;
    }
    // Caller owns the order lock before touching its reservation or inventory.
    public void confirm(Order order) {
        var hold=reservations.find(order.id);
        if(!hold.status.equals("ACTIVE")) throw new IllegalStateException("Cannot confirm released stock");
        moveStock(order,true); hold.status="CONFIRMED"; order.status="CONFIRMED";
    }
    public void release(Order order) {
        var hold=reservations.find(order.id);
        if(!hold.status.equals("ACTIVE")) return;
        moveStock(order,false); hold.status="EXPIRED"; order.status="EXPIRED";
    }
    private void moveStock(Order order,boolean confirm) {
        var items=orders.items(order.id);
        var stocks=sales.lockItems(items.stream().map(i->i.saleItemId).toList());
        var quantity=new HashMap<UUID,Integer>();
        items.forEach(i->quantity.put(i.saleItemId,i.quantity));
        for(var stock:stocks) {
            int n=quantity.get(stock.id); stock.held-=n;
            if(confirm) stock.sold+=n; else stock.available+=n;
        }
    }
    @Transactional(readOnly=true)
    public List<UUID> due() { return reservations.due(clock.instant()); }
    @Transactional
    public boolean expire(UUID id) {
        var order=orders.lock(id);
        if(order==null || !order.status.equals("PAYMENT_PENDING")) return false;
        var hold=reservations.find(id);
        if(!hold.status.equals("ACTIVE") || hold.holdExpiresAt.isAfter(clock.instant())) return false;
        release(order); return true;
    }
}
