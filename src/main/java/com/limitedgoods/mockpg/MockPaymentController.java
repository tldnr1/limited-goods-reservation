package com.limitedgoods.mockpg;
import com.limitedgoods.payments.*;
import com.limitedgoods.config.ApiError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController @ConditionalOnProperty(name="app.mock.enabled",havingValue="true")
public class MockPaymentController {
    private final MockPaymentStore store;
    private final String secret;
    public MockPaymentController(MockPaymentStore store,@Value("${app.pg-secret}") String secret) { this.store=store; this.secret=secret; }
    @PostMapping("/mock/payments")
    public ResponseEntity<?> accept(@RequestHeader("X-PG-Secret") String supplied,@RequestBody PaymentService.Work request) {
        if(!secret.equals(supplied)) throw new ApiError(403,"INVALID_PG_SECRET");
        if(request.id()==null || request.amount()<=0 || request.scenario()==null || request.deadline()==null)
            throw new ApiError(400,"INVALID_REQUEST");
        var result=store.accept(request); // Provider commits before deliberately losing response.
        if(result.loseResponse()) return ResponseEntity.status(504).build();
        return ResponseEntity.ok(result.response());
    }
}
