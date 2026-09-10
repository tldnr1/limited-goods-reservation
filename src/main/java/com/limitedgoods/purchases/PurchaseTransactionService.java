package com.limitedgoods.purchases;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.limitedgoods.config.ApiError;
import com.limitedgoods.sales.SaleRepository;
import com.limitedgoods.reservations.ReservationService;
import com.limitedgoods.admission.AdmissionGate;
@Service
public class PurchaseTransactionService {
    private final OrderRepository orders;
    private final SaleRepository sales;
    private final ReservationService reservations;
    private final OrderQueryService query;
    private final AdmissionGate gate;
    private final Clock clock;
    public PurchaseTransactionService(OrderRepository orders,SaleRepository sales,ReservationService reservations,
                                      OrderQueryService query,AdmissionGate gate,Clock clock) {
        this.orders=orders; this.sales=sales; this.reservations=reservations; this.query=query; this.gate=gate; this.clock=clock;
    }
    @Transactional(readOnly=true)
    public OrderView replay(String user,String key,PurchaseRequest request) {
        var existing=orders.byKey(user,key);
        if(existing==null) return null;
        if(!existing.fingerprint.equals(request.fingerprint())) throw new ApiError(409,"IDEMPOTENCY_KEY_REUSED");
        return query.view(existing);
    }
    @Transactional
    public OrderView purchase(String user,String key,PurchaseRequest request,PurchaseTrace trace,Long admissionExpiresAt) {
        trace.next("request_preparation");
        String fingerprint=request.fingerprint();
        var now=clock.instant();
        trace.next("idempotency_lock_query");
        orders.serializeKey(user,key);
        trace.next("idempotency_lookup");
        var existing=orders.byKey(user,key);
        if(existing!=null) {
            if(!existing.fingerprint.equals(fingerprint)) throw new ApiError(409,"IDEMPOTENCY_KEY_REUSED");
            trace.next("response_queries_and_auto_flush");
            var view=query.view(existing);
            trace.next("transaction_completion");
            return view;
        }
        trace.next("sale_lookup_and_cache");
        var sale=sales.find(request.saleId());
        if(sale==null) throw new ApiError(404,"SALE_NOT_FOUND");
        if(sale.opensAt.isAfter(now)) throw new ApiError(409,"SALE_NOT_OPEN");
        // Durable idempotency is checked before this bounded, advisory negative cache.
        for(var item:request.items()) if(gate.unavailable(item.saleItemId())) throw new ApiError(409,"TEMPORARILY_UNAVAILABLE");
        trace.next("inventory_lock_query");
        var stocks=sales.lockItems(request.items().stream().map(PurchaseRequest.Item::saleItemId).toList());
        trace.inventoryLocked();
        if(admissionExpiresAt!=null && clock.millis()>=admissionExpiresAt) throw new ApiError(409,"ADMISSION_EXPIRED");
        trace.next("stock_validation");
        if(stocks.size()!=request.items().size() || stocks.stream().anyMatch(i->!i.saleId.equals(sale.id)))
            throw new ApiError(400,"INVALID_SALE_ITEM");
        var quantities=new HashMap<UUID,Integer>();
        request.items().forEach(i->quantities.put(i.saleItemId(),i.quantity()));
        for(var stock:stocks) {
            int quantity=quantities.get(stock.id);
            if(stock.available<quantity) {
                if(stock.available==0) gate.rememberUnavailable(stock.id);
                throw new ApiError(409,"TEMPORARILY_UNAVAILABLE");
            }
            trace.next("user_limit_query");
            if(orders.used(user,stock.id)+quantity>stock.perUserLimit) throw new ApiError(409,"USER_LIMIT_EXCEEDED");
            trace.next("stock_validation");
        }
        trace.next("order_persist");
        var order=new Order(user,sale.id,key,fingerprint,clock.instant());
        for(var stock:stocks) order.totalAmount+=stock.price*quantities.get(stock.id);
        orders.save(order);
        trace.next("items_persist_and_stock_changes");
        var responseItems=new ArrayList<OrderView.Item>();
        for(var stock:stocks) {
            int quantity=quantities.get(stock.id);
            stock.available-=quantity; stock.held+=quantity;
            orders.save(new OrderItem(order.id,stock.id,quantity,stock.price));
            responseItems.add(new OrderView.Item(stock.id,quantity,stock.price));
        }
        trace.next("reservation_persist");
        var hold=reservations.create(order);
        // No payments exist yet: avoid three response queries and auto-flush while holding stock locks.
        var view=new OrderView(order.id,order.status,order.totalAmount,hold.holdExpiresAt,responseItems,List.of());
        trace.next("transaction_completion");
        return view;
    }
}
