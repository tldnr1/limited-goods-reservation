package com.limitedgoods.purchases;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

// One record after the transaction proxy exits; never logs while holding the inventory lock.
final class PurchaseTrace {
    private static final Logger log=LoggerFactory.getLogger(PurchaseTrace.class);
    private final boolean enabled=log.isInfoEnabled();
    private final long started=enabled?System.nanoTime():0;
    private long checkpoint=started;
    private long locked;
    private String phase="transaction_entry";
    private final Map<String,Double> stages=new LinkedHashMap<>();
    private String requestId() {
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String value=attributes.getRequest().getHeader("X-Request-ID");
            // Nginx overwrites this header. Direct callers cannot inject arbitrary log text.
            if(value!=null && value.matches("[a-f0-9]{32}")) return value;
        }
        return null;
    }
    void next(String name) {
        if(!enabled) return;
        long now=System.nanoTime();
        stages.merge(phase,(now-checkpoint)/1_000_000.0,Double::sum);
        checkpoint=now; phase=name;
    }
    void inventoryLocked() { if(enabled) locked=System.nanoTime(); }
    void finish(UUID saleId,UUID orderId,Throwable failure) {
        if(!enabled) return;
        String finalPhase=phase;
        next("finished");
        log.info("purchase_timing request_id={} sale={} order={} outcome={} final_phase={} total_ms={} after_lock_until_proxy_exit_ms={} stages_ms={}",
            requestId(),saleId,orderId,failure==null?"success":failure.getClass().getSimpleName(),finalPhase,
            (checkpoint-started)/1_000_000.0,locked==0?null:(checkpoint-locked)/1_000_000.0,stages);
    }
}
