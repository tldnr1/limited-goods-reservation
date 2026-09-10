package com.limitedgoods.payments;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import com.limitedgoods.config.ApiError;
import com.limitedgoods.purchases.OrderView;
@RestController @org.springframework.context.annotation.Profile({"all","payment"})
public class PaymentController {
    public record Start(@NotNull PaymentService.Scenario scenario) {}
    public record Callback(@NotNull UUID attemptId,@Positive long amount,@NotNull PaymentService.Result result) {}
    private final PaymentService payments;
    private final String secret;
    public PaymentController(PaymentService payments,@Value("${app.pg-secret}") String secret) { this.payments=payments; this.secret=secret; }
    @PostMapping("/api/orders/{id}/payments") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public OrderView.Payment start(@PathVariable UUID id,@RequestHeader("X-User-Id") String user,
        @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Start request) {
        return payments.start(id,user,key,request.scenario());
    }
    @PostMapping("/api/payments/callback") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void callback(@RequestHeader("X-PG-Secret") String supplied,@Valid @RequestBody Callback request) {
        if(!java.security.MessageDigest.isEqual(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8))) throw new ApiError(403,"INVALID_PG_SECRET");
        payments.apply(request.attemptId(),request.amount(),request.result());
    }
}
