package com.limitedgoods.purchases;
import org.springframework.stereotype.Service;
import com.limitedgoods.admission.AdmissionGate;
import com.limitedgoods.config.Identity;
@Service
public class PurchaseService {
    private final AdmissionGate gate;
    private final PurchaseTransactionService transactions;
    public PurchaseService(AdmissionGate gate,PurchaseTransactionService transactions) { this.gate=gate; this.transactions=transactions; }
    public OrderView purchase(String user,String key,PurchaseRequest request) {
        Identity.validate(user,key);
        request.fingerprint();
        String token=gate.enter();
        var trace=new PurchaseTrace();
        OrderView result=null;
        Throwable failure=null;
        try { result=transactions.purchase(user,key,request,trace); return result; }
        catch(RuntimeException | Error e) { failure=e; throw e; }
        finally {
            // Separate bean: commit/rollback and connection cleanup have already completed.
            trace.finish(request.saleId(),result==null?null:result.id(),failure);
            gate.leave(token);
        }
    }
}
