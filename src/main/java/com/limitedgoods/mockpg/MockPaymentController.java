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
    private final int delayMs;
    public MockPaymentController(MockPaymentStore store,@Value("${app.pg-secret}") String secret,
                                 @Value("${app.mock.delay-ms:0}") int delayMs) {
        if(delayMs<0 || delayMs>5000) throw new IllegalArgumentException("Mock PG delay must be 0..5000 ms");
        this.store=store; this.secret=secret; this.delayMs=delayMs;
    }
    @PostMapping("/mock/payments")
    public ResponseEntity<?> accept(@RequestHeader("X-PG-Secret") String supplied,@RequestBody PaymentService.Work request) {
        if(!secret.equals(supplied)) throw new ApiError(403,"INVALID_PG_SECRET");
        if(request.id()==null || request.amount()<=0 || request.scenario()==null || request.deadline()==null)
            throw new ApiError(400,"INVALID_REQUEST");
        var result=store.accept(request); // Provider commits before deliberately losing response.
        // Artificial response latency never holds the store transaction/DB connection.
        if(delayMs>0) {
            try { Thread.sleep(delayMs); }
            catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                return ResponseEntity.status(503).build();
            }
        }
        if(result.loseResponse()) return ResponseEntity.status(504).build();
        return ResponseEntity.ok(result.response());
    }
}
