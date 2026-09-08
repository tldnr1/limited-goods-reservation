package com.limitedgoods.mockpg;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import com.limitedgoods.payments.*;
import com.limitedgoods.config.ApiError;
@Service
public class MockPaymentStore {
    public record Receipt(PaymentProvider.Response response,boolean loseResponse) {}
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public MockPaymentStore(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc; this.clock=clock; }
    @Transactional
    public Receipt accept(PaymentService.Work work) {
        Instant now=clock.instant();
        String initial=!now.isBefore(work.deadline())?"FAILED":switch(work.scenario()) {
            case FAILURE->"FAILED";
            case UNKNOWN,DELAYED_SUCCESS->"UNKNOWN";
            default->"SUCCEEDED";
        };
        int inserted=jdbc.update("""
            insert into mock_pg_receipts(attempt_id,amount,scenario,accepted_at,deadline,result)
            values(?,?,?,?,?,?) on conflict(attempt_id) do nothing
            """,work.id(),work.amount(),work.scenario().name(),Timestamp.from(now),Timestamp.from(work.deadline()),initial);
        var rows=jdbc.queryForList("select * from mock_pg_receipts where attempt_id=? for update",work.id());
        var row=rows.getFirst();
        if(((Number)row.get("amount")).longValue()!=work.amount() || !row.get("scenario").equals(work.scenario().name()))
            throw new ApiError(409,"PG_IDEMPOTENCY_CONFLICT");
        String result=(String)row.get("result");
        if(work.scenario()==PaymentService.Scenario.DELAYED_SUCCESS && result.equals("UNKNOWN") &&
           !((Timestamp)row.get("accepted_at")).toInstant().plusSeconds(3).isAfter(now)) {
            result="SUCCEEDED";
            jdbc.update("update mock_pg_receipts set result=? where attempt_id=?",result,work.id());
        }
        return new Receipt(new PaymentProvider.Response(work.id(),work.amount(),PaymentService.Result.valueOf(result)),
            inserted==1 && work.scenario()==PaymentService.Scenario.LOST_RESPONSE);
    }
}
