package com.limitedgoods.waiting;
import com.limitedgoods.purchases.PurchaseRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admissions") @Profile({"all","waiting"})
public class WaitingController {
    private final WaitingService waiting;
    public WaitingController(WaitingService waiting) { this.waiting=waiting; }
    @PostMapping
    public ResponseEntity<WaitingService.View> join(@RequestHeader("X-User-Id") String user,
        @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody PurchaseRequest request) {
        return response(202,waiting.join(user,key,request));
    }
    @GetMapping("/{id}")
    public ResponseEntity<WaitingService.View> get(@RequestHeader("X-User-Id") String user,@PathVariable String id) {
        return response(200,waiting.get(user,id));
    }
    private ResponseEntity<WaitingService.View> response(int status,WaitingService.View view) {
        var builder=ResponseEntity.status(status).header("Cache-Control","no-store");
        if(view.retryAfter()>0) builder.header("Retry-After",""+view.retryAfter());
        return builder.body(view);
    }
}
