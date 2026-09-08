package com.limitedgoods.payments;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.limitedgoods.config.*;
import com.limitedgoods.purchases.*;
import com.limitedgoods.reservations.*;
import io.micrometer.core.instrument.MeterRegistry;
@Service
public class PaymentService {
    public enum Scenario { SUCCESS, FAILURE, DELAYED_SUCCESS, LOST_RESPONSE, UNKNOWN }
    public enum Result { SUCCEEDED, FAILED, UNKNOWN }
    public record Work(UUID id,UUID orderId,long amount,Scenario scenario,java.time.Instant deadline) {}
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final ReservationRepository holds;
    private final ReservationService reservations;
    private final Clock clock;
    private final long grace;
    private final MeterRegistry metrics;
    public PaymentService(OrderRepository orders,PaymentRepository payments,ReservationRepository holds,
                          ReservationService reservations,Clock clock,@Value("${app.grace-seconds}") long grace,MeterRegistry metrics) {
        this.orders=orders; this.payments=payments; this.holds=holds; this.reservations=reservations;
        this.clock=clock; this.grace=grace; this.metrics=metrics;
    }
    @Transactional
    public OrderView.Payment start(UUID orderId,String user,String key,Scenario scenario) {
        Identity.validate(user,key);
        var order=orders.lock(orderId);
        if(order==null || !order.userId.equals(user)) throw new ApiError(404,"ORDER_NOT_FOUND");
        var attempts=payments.forOrder(orderId);
        for(var attempt:attempts) if(attempt.idempotencyKey.equals(key)) {
            if(!attempt.scenario.equals(scenario.name())) throw new ApiError(409,"IDEMPOTENCY_KEY_REUSED");
            return view(attempt);
        }
        if(attempts.stream().anyMatch(p->!p.status.equals("FAILED"))) throw new ApiError(409,"PAYMENT_ATTEMPT_BLOCKED");
        var hold=holds.find(orderId);
        if(!order.status.equals("PAYMENT_PENDING") || !hold.holdExpiresAt.isAfter(clock.instant()))
            throw new ApiError(409,"HOLD_EXPIRED");
        var attempt=new PaymentAttempt(orderId,key,scenario.name(),order.totalAmount,clock.instant());
        payments.save(attempt);
        order.status="PAYMENT_PROCESSING";
        hold.confirmationDeadline=hold.holdExpiresAt.plusSeconds(grace);
        return view(attempt);
    }
    private OrderView.Payment view(PaymentAttempt p) { return new OrderView.Payment(p.id,p.status,p.scenario); }
    @Transactional(readOnly=true)
    public Work work(UUID id) {
        var p=payments.find(id);
        if(p==null || p.terminal()) return null;
        return new Work(p.id,p.orderId,p.amount,Scenario.valueOf(p.scenario),holds.find(p.orderId).confirmationDeadline);
    }
    @Transactional
    public void apply(UUID id,long amount,Result result) {
        UUID orderId=payments.orderId(id);
        if(orderId==null) throw new ApiError(404,"PAYMENT_NOT_FOUND");
        var order=orders.lock(orderId);
        // Fetch the entity only after the order lock so callbacks/workers see fresh status.
        var attempt=payments.find(id);
        if(attempt.amount!=amount) throw new ApiError(409,"PAYMENT_AMOUNT_MISMATCH");
        if(attempt.terminal()) {
            if(result!=Result.UNKNOWN && !attempt.status.equals(result.name())) throw new ApiError(409,"PAYMENT_RESULT_CONFLICT");
            return;
        }
        attempt.status=result.name();
        attempt.leaseUntil=null;
        attempt.nextCheckAt=clock.instant().plusSeconds(1);
        if(result==Result.SUCCEEDED) reservations.confirm(order);
        else if(result==Result.FAILED) {
            order.status="PAYMENT_PENDING";
            if(!holds.find(orderId).holdExpiresAt.isAfter(clock.instant())) reservations.release(order);
        }
        metrics.counter("goods.payment.results","result",result.name()).increment();
    }
}
