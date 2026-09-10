package com.limitedgoods.admission;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;
import com.limitedgoods.config.ApiError;
import io.micrometer.core.instrument.MeterRegistry;
@Component
public class AdmissionGate {
    private static final DefaultRedisScript<Long> ENTER=new DefaultRedisScript<>("""
        local t=redis.call('TIME')
        local now=t[1]*1000+math.floor(t[2]/1000)
        redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now)
        if redis.call('ZCARD',KEYS[1])>=tonumber(ARGV[2]) then return 0 end
        if tonumber(ARGV[3])>0 then
          local next=tonumber(redis.call('GET',KEYS[2]) or '0')
          if now<next then return 0 end
          local interval=math.ceil(1000/tonumber(ARGV[3]))
          redis.call('SET',KEYS[2],now+interval,'PX',interval+1000)
        end
        redis.call('ZADD',KEYS[1],now+10000,ARGV[1])
        redis.call('PEXPIRE',KEYS[1],11000)
        return 1
        """,Long.class);
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final String namespace;
    private final int permits;
    private final int rate;
    private final MeterRegistry metrics;
    public AdmissionGate(StringRedisTemplate redis,@Value("${app.admission.enabled}") boolean enabled,
                         @Value("${app.admission.namespace}") String namespace,
                         @Value("${app.admission.permits}") int permits,MeterRegistry metrics,
                         @Value("${app.admission.rate:0}") int rate) {
        this.redis=redis; this.enabled=enabled; this.namespace=namespace; this.permits=permits; this.metrics=metrics; this.rate=rate;
    }
    public String enter() {
        if(!enabled) return null;
        String token=UUID.randomUUID().toString();
        try {
            if(!Long.valueOf(1).equals(redis.execute(ENTER,List.of(namespace+"inflight",namespace+"purchase:rate"),token,""+permits,""+rate))) {
                metrics.counter("goods.admission","outcome","limited").increment();
                throw new ApiError(429,"PURCHASE_BUSY");
            }
            return token;
        } catch(DataAccessException e) {
            metrics.counter("goods.admission","outcome","unavailable").increment();
            throw new ApiError(503,"ADMISSION_UNAVAILABLE");
        }
    }
    public void leave(String token) {
        if(token==null) return;
        try { redis.opsForZSet().remove(namespace+"inflight",token); }
        catch(DataAccessException e) { metrics.counter("goods.admission","outcome","cleanup_failed").increment(); }
    }
    public void completeReady(String id) {
        try { redis.opsForZSet().remove(namespace+"waiting:ready",id); }
        catch(DataAccessException e) { metrics.counter("goods.admission","outcome","cleanup_failed").increment(); }
    }
    public boolean unavailable(UUID item) {
        if(!enabled) return false;
        try { return Boolean.TRUE.equals(redis.hasKey(namespace+"empty:"+item)); }
        catch(DataAccessException e) { return false; } // Already admitted; DB remains authoritative.
    }
    public void rememberUnavailable(UUID item) {
        if(!enabled) return;
        try { redis.opsForValue().set(namespace+"empty:"+item,"1",Duration.ofSeconds(1)); }
        catch(DataAccessException e) { metrics.counter("goods.admission","outcome","cache_failed").increment(); }
    }
}
