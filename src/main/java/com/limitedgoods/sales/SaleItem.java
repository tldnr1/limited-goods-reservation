package com.limitedgoods.sales;
import jakarta.persistence.*;
import java.util.UUID;
@Entity @Table(name="sale_items")
public class SaleItem {
    @Id public UUID id;
    public UUID saleId;
    public String name;
    public long price;
    public int perUserLimit;
    public int total;
    public int available;
    public int held;
    public int sold;
    protected SaleItem() {}
    public SaleItem(UUID saleId, String name, long price, int total, int limit) {
        this.id=UUID.randomUUID(); this.saleId=saleId; this.name=name; this.price=price;
        this.total=total; this.available=total; this.perUserLimit=limit;
    }
}
