package com.limitedgoods.sales;
import java.sql.Timestamp;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.MeterRegistry;

// Bounded-frequency projection, never called by a waiting HTTP request. PostgreSQL remains authoritative.
@Configuration @EnableScheduling
@ConditionalOnProperty(name="app.catalog.enabled",havingValue="true")
public class SaleCatalogPublisher {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final String prefix;
    private final MeterRegistry metrics;
    public SaleCatalogPublisher(JdbcTemplate jdbc,StringRedisTemplate redis,
                                @Value("${app.admission.namespace}") String prefix,MeterRegistry metrics) {
        this.jdbc=jdbc; this.redis=redis; this.prefix=prefix; this.metrics=metrics;
    }
    @Scheduled(fixedDelayString="${app.catalog.refresh-ms:1000}")
    @Transactional
    public void refresh() {
        try {
            // Warmup cleanup takes the exclusive peer before deleting its sale. No stale
            // publisher can write that sale back after cleanup has acquired the lock.
            jdbc.execute("select pg_advisory_xact_lock_shared(74190321)");
            var rows=jdbc.queryForList("""
                select s.id,s.opens_at,sum(i.available) available,sum(i.held) held
                from sales s join sale_items i on i.sale_id=s.id group by s.id,s.opens_at
                """);
            for(var row:rows) {
                String state=((Number)row.get("available")).longValue()>0?"AVAILABLE":
                    ((Number)row.get("held")).longValue()>0?"FULLY_HELD":"SOLD_OUT";
                String value=((Timestamp)row.get("opens_at")).toInstant().toEpochMilli()+":"+state;
                redis.opsForValue().set(prefix+"catalog:"+row.get("id"),value,Duration.ofSeconds(5));
            }
        } catch(DataAccessException e) { metrics.counter("goods.catalog.errors").increment(); }
    }
}
