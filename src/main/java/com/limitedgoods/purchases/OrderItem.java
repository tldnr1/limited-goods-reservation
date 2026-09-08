package com.limitedgoods.purchases;
import jakarta.persistence.*;
import java.util.UUID;
@Entity @Table(name="order_items")
public class OrderItem {
    @Id public UUID id;
    public UUID orderId;
    public UUID saleItemId;
    public int quantity;
    public long unitPrice;
    protected OrderItem() {}
    public OrderItem(UUID order, UUID item, int quantity, long price) {
        id=UUID.randomUUID(); orderId=order; saleItemId=item; this.quantity=quantity; unitPrice=price;
    }
}
