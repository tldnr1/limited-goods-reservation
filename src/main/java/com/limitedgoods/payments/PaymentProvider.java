package com.limitedgoods.payments;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
@Component
public class PaymentProvider {
    public record Response(java.util.UUID attemptId,long amount,PaymentService.Result result) {}
    private final HttpClient client;
    private final ObjectMapper json;
    private final String url;
    private final String secret;
    public PaymentProvider(HttpClient client,ObjectMapper json,@Value("${app.pg-url}") String url,
                           @Value("${app.pg-secret}") String secret) {
        this.client=client; this.json=json; this.url=url; this.secret=secret;
    }
    public PaymentService.Result resolve(PaymentService.Work work) {
        try {
            var request=HttpRequest.newBuilder(URI.create(url+"/mock/payments"))
                .timeout(Duration.ofSeconds(2)).header("Content-Type","application/json").header("X-PG-Secret",secret)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(work))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) return PaymentService.Result.UNKNOWN;
            var body=json.readValue(response.body(),Response.class);
            if(!work.id().equals(body.attemptId()) || work.amount()!=body.amount() || body.result()==null)
                return PaymentService.Result.UNKNOWN;
            return body.result();
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt(); return PaymentService.Result.UNKNOWN;
        } catch(java.io.IOException e) { return PaymentService.Result.UNKNOWN; }
    }
}
