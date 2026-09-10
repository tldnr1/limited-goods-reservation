package com.limitedgoods.waiting;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitedgoods.config.*;
import com.limitedgoods.purchases.PurchaseRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service @Profile({"all","waiting"})
public class WaitingService {
    public record View(String id,String state,long expiresAt,int retryAfter,String ticket) {}
    private static final DefaultRedisScript<String> SCRIPT=new DefaultRedisScript<>();
    static { SCRIPT.setLocation(new ClassPathResource("waiting.lua")); SCRIPT.setResultType(String.class); }
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final AdmissionTicket tickets;
    private final String prefix;
    private final int rate,readyLimit,maxWaiting;
    public WaitingService(StringRedisTemplate redis,ObjectMapper json,AdmissionTicket tickets,
        @Value("${app.admission.namespace}") String prefix,@Value("${app.waiting.rate:25}") int rate,
        @Value("${app.waiting.ready-limit:50}") int readyLimit,@Value("${app.waiting.max-waiting:50000}") int maxWaiting) {
        if(rate<1 || readyLimit<1 || maxWaiting<1) throw new IllegalArgumentException("Invalid waiting limits");
        this.redis=redis; this.json=json; this.tickets=tickets; this.prefix=prefix;
        this.rate=rate; this.readyLimit=readyLimit; this.maxWaiting=maxWaiting;
    }
    public View join(String user,String key,PurchaseRequest request) {
        Identity.validate(user,key);
        try {
            String id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((user.length()+":"+user+key).getBytes(StandardCharsets.UTF_8)));
            String data=json.writeValueAsString(new AdmissionTicket.Claims(id,user,key,request.fingerprint(),0));
            return execute("join",user,id,data,request.saleId().toString());
        } catch(java.security.GeneralSecurityException | java.io.IOException e) { throw new IllegalStateException(e); }
    }
    public View get(String user,String id) {
        if(!id.matches("[a-f0-9]{64}")) throw new ApiError(404,"ADMISSION_NOT_FOUND");
        return execute("poll",user,id,"","");
    }
    private View execute(String mode,String user,String id,String data,String sale) {
        try {
            var keys=List.of(prefix+"waiting:item:"+id,prefix+"waiting:queue",prefix+"waiting:ready",
                prefix+"waiting:rate",prefix+"waiting:live");
            String raw=redis.execute(SCRIPT,keys,mode,user,id,prefix,data,sale,"180000","10000",
                ""+readyLimit,""+maxWaiting,""+(long)Math.ceil(1000.0/rate));
            var value=json.readTree(raw);
            String state=value.get("state").asText();
            switch(state) {
                case "UNAVAILABLE" -> throw new ApiError(503,"WAITING_UNAVAILABLE");
                case "NOT_OPEN" -> throw new ApiError(409,"SALE_NOT_OPEN");
                case "CONFLICT" -> throw new ApiError(409,"IDEMPOTENCY_KEY_REUSED");
                case "FULL" -> throw new ApiError(429,"WAITING_FULL");
                case "NOT_FOUND" -> throw new ApiError(404,"ADMISSION_NOT_FOUND");
            }
            long expires=value.get("expiresAt").asLong();
            String token=null;
            if(state.equals("READY")) {
                var original=json.readValue(value.get("data").asText(),AdmissionTicket.Claims.class);
                token=tickets.issue(new AdmissionTicket.Claims(id,user,original.key(),original.fingerprint(),expires));
            }
            return new View(id,state,expires,value.get("retryAfter").asInt(),token);
        } catch(DataAccessException e) { throw new ApiError(503,"WAITING_UNAVAILABLE"); }
        catch(java.io.IOException e) { throw new IllegalStateException(e); }
    }
}
