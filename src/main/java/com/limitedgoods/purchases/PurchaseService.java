package com.limitedgoods.purchases;
import org.springframework.stereotype.Service;
import com.limitedgoods.admission.AdmissionGate;
import com.limitedgoods.config.Identity;
import com.limitedgoods.config.AdmissionTicket;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.core.instrument.MeterRegistry;
@Service
public class PurchaseService {
    private final AdmissionGate gate;
    private final PurchaseTransactionService transactions;
    private final AdmissionTicket tickets;
    private final boolean ticketRequired;
    private final MeterRegistry metrics;
    public PurchaseService(AdmissionGate gate,PurchaseTransactionService transactions,AdmissionTicket tickets,
                           @Value("${app.waiting.required:false}") boolean ticketRequired,MeterRegistry metrics) {
        this.gate=gate; this.transactions=transactions; this.tickets=tickets; this.ticketRequired=ticketRequired;
        this.metrics=metrics;
    }
    public OrderView purchase(String user,String key,PurchaseRequest request) {
        return purchase(user,key,request,null);
    }
    public OrderView purchase(String user,String key,PurchaseRequest request,String suppliedTicket) {
        Identity.validate(user,key);
        request.fingerprint();
        AdmissionTicket.Claims claims=null;
        if(ticketRequired) {
            claims=tickets.verify(suppliedTicket,user,key,request);
            // A committed order survives both ticket expiry and Redis failure.
            var replay=transactions.replay(user,key,request);
            if(replay!=null) return replay;
            tickets.requireFresh(claims);
        }
        String token=gate.enter();
        var trace=new PurchaseTrace(metrics);
        OrderView result=null;
        Throwable failure=null;
        try { result=transactions.purchase(user,key,request,trace,claims==null?null:claims.expiresAt()); return result; }
        catch(RuntimeException | Error e) { failure=e; throw e; }
        finally {
            // Separate bean: commit/rollback and connection cleanup have already completed.
            trace.finish(request.saleId(),result==null?null:result.id(),failure);
            gate.leave(token);
            if(claims!=null && result!=null) gate.completeReady(claims.admissionId());
        }
    }
}
