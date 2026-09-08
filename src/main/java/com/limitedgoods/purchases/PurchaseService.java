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
        try { return transactions.purchase(user,key,request); }
        finally { gate.leave(token); } // Separate bean: transaction commit happens before permit release.
    }
}
