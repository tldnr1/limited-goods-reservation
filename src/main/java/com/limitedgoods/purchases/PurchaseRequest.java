package com.limitedgoods.purchases;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.util.stream.Collectors;
import com.limitedgoods.config.ApiError;
public record PurchaseRequest(@NotNull UUID saleId, @NotEmpty @Size(max=20) List<@NotNull @Valid Item> items) {
    public record Item(@NotNull UUID saleItemId, @Positive @Max(1000000) int quantity) {}
    public String fingerprint() {
        if(items.stream().map(Item::saleItemId).distinct().count()!=items.size())
            throw new ApiError(400,"DUPLICATE_ITEM");
        return saleId+":"+items.stream().sorted(Comparator.comparing(Item::saleItemId))
            .map(i->i.saleItemId()+"="+i.quantity()).collect(Collectors.joining(","));
    }
}
