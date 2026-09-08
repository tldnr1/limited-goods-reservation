package com.limitedgoods.sales;
import java.time.Instant;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.limitedgoods.config.ApiError;
@Service
public class SaleService {
    public record ItemInput(@NotBlank @Size(max=200) String name, @Positive @Max(1000000000) long price,
                            @Positive @Max(1000000) int total, @Positive @Max(1000000) int perUserLimit) {}
    public record Create(@NotBlank @Size(max=200) String name, @NotNull Instant opensAt,
                         @NotEmpty @Size(max=20) List<@NotNull @Valid ItemInput> items) {}
    public record Item(UUID id, String name, long price, int perUserLimit, int total, int available, int held, int sold) {}
    public record View(UUID id, String name, Instant opensAt, List<Item> items) {}
    private final SaleRepository repository;
    public SaleService(SaleRepository repository) { this.repository=repository; }
    @Transactional
    public View create(Create input) {
        var sale=new Sale(input.name(),input.opensAt()); repository.save(sale);
        for(var item:input.items()) repository.save(new SaleItem(sale.id,item.name(),item.price(),item.total(),item.perUserLimit()));
        return get(sale.id);
    }
    @Transactional(readOnly=true)
    public View get(UUID id) {
        var sale=repository.find(id);
        if(sale==null) throw new ApiError(404,"SALE_NOT_FOUND");
        return new View(id,sale.name,sale.opensAt,repository.items(id).stream()
            .map(i->new Item(i.id,i.name,i.price,i.perUserLimit,i.total,i.available,i.held,i.sold)).toList());
    }
}
