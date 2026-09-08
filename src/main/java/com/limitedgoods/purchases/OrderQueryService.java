package com.limitedgoods.purchases;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.limitedgoods.config.ApiError;
import com.limitedgoods.payments.PaymentRepository;
import com.limitedgoods.reservations.ReservationRepository;
@Service
public class OrderQueryService {
    private final OrderRepository orders;
    private final ReservationRepository reservations;
    private final PaymentRepository payments;
    public OrderQueryService(OrderRepository orders,ReservationRepository reservations,PaymentRepository payments) {
        this.orders=orders; this.reservations=reservations; this.payments=payments;
    }
    @Transactional(readOnly=true)
    public OrderView get(UUID id,String user) {
        var order=orders.find(id);
        if(order==null || !order.userId.equals(user)) throw new ApiError(404,"ORDER_NOT_FOUND");
        return view(order);
    }
    public OrderView view(Order order) {
        var hold=reservations.find(order.id);
        return new OrderView(order.id,order.status,order.totalAmount,hold.holdExpiresAt,
            orders.items(order.id).stream().map(i->new OrderView.Item(i.saleItemId,i.quantity,i.unitPrice)).toList(),
            payments.forOrder(order.id).stream().map(p->new OrderView.Payment(p.id,p.status,p.scenario)).toList());
    }
}
