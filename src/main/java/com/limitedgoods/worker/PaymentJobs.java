package com.limitedgoods.worker;
import java.util.UUID;
import java.time.Clock;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class PaymentJobs {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public PaymentJobs(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc; this.clock=clock; }
    @Transactional
    public UUID claim() {
        var now=clock.instant();
        var ids=jdbc.query("""
            update payment_attempts set lease_until=?
            where id=(select id from payment_attempts
              where status in ('CREATED','PROCESSING','UNKNOWN')
              and next_check_at<=? and (lease_until is null or lease_until<=?)
              order by next_check_at,id for update skip locked limit 1)
            returning id
            """,(rs,n)->rs.getObject(1,UUID.class),Timestamp.from(now.plusSeconds(10)),Timestamp.from(now),Timestamp.from(now));
        return ids.isEmpty()?null:ids.getFirst();
    }
}
