package com.limitedgoods.config;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitedgoods.purchases.PurchaseRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AdmissionTicket {
    public record Claims(String admissionId,String user,String key,String fingerprint,long expiresAt) {}
    private final ObjectMapper json;
    private final Clock clock;
    private final byte[] secret;
    public AdmissionTicket(ObjectMapper json,Clock clock,@Value("${app.waiting.secret:local-only-ticket-signing-secret-change-before-deploy}") String secret) {
        if(secret.length()<32) throw new IllegalArgumentException("Ticket secret must have at least 32 characters");
        this.json=json; this.clock=clock; this.secret=secret.getBytes(StandardCharsets.UTF_8);
    }
    public String issue(Claims claims) {
        try {
            String body=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claims));
            return body+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(body));
        } catch(java.io.IOException e) { throw new IllegalStateException(e); }
    }
    public Claims verify(String token,String user,String key,PurchaseRequest request) {
        if(token==null) throw new ApiError(403,"ADMISSION_REQUIRED");
        try {
            if(token.length()>8192) throw new IllegalArgumentException();
            String[] parts=token.split("\\.",-1);
            if(parts.length!=2 || !MessageDigest.isEqual(mac(parts[0]),Base64.getUrlDecoder().decode(parts[1])))
                throw new IllegalArgumentException();
            var claims=json.readValue(Base64.getUrlDecoder().decode(parts[0]),Claims.class);
            if(!user.equals(claims.user()) || !key.equals(claims.key()) || !request.fingerprint().equals(claims.fingerprint())
                || claims.admissionId()==null) throw new IllegalArgumentException();
            return claims;
        } catch(java.io.IOException | IllegalArgumentException e) { throw new ApiError(403,"INVALID_ADMISSION"); }
    }
    public void requireFresh(Claims claims) {
        if(claims.expiresAt()<=clock.millis()) throw new ApiError(409,"ADMISSION_EXPIRED");
    }
    private byte[] mac(String body) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch(java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
