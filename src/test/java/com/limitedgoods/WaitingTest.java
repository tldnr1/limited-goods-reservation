package com.limitedgoods;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitedgoods.config.AdmissionTicket;
import com.limitedgoods.purchases.PurchaseRequest;
import com.limitedgoods.waiting.WaitingService;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;

class WaitingTest {
    LettuceConnectionFactory connection;
    StringRedisTemplate redis;
    String prefix;
    WaitingService waiting;
    AdmissionTicket tickets;
    PurchaseRequest request;
    @BeforeEach void setup() {
        connection=new LettuceConnectionFactory("localhost",6380); connection.afterPropertiesSet(); connection.start();
        redis=new StringRedisTemplate(connection); prefix="goods:test:waiting:"+UUID.randomUUID()+":";
        var json=new ObjectMapper(); tickets=new AdmissionTicket(json,Clock.systemUTC(),"a-local-test-secret-of-at-least-32-characters");
        waiting=new WaitingService(redis,json,tickets,prefix,25,2,10);
        request=new PurchaseRequest(UUID.randomUUID(),List.of(new PurchaseRequest.Item(UUID.randomUUID(),1)));
        redis.opsForValue().set(prefix+"catalog:"+request.saleId(),"0:AVAILABLE");
    }
    @AfterEach void cleanup() { redis.delete(redis.keys(prefix+"*")); connection.destroy(); }
    @Test void joinDoesNotReserveAndPollingIssuesBoundNonExtendingTicket() {
        var joined=waiting.join("u","k",request);
        assertThat(joined.state()).isEqualTo("WAITING"); assertThat(joined.ticket()).isNull();
        assertThat(waiting.join("u","k",request).id()).isEqualTo(joined.id());
        var ready=waiting.get("u",joined.id());
        assertThat(ready.state()).isEqualTo("READY");
        assertThat(waiting.get("u",joined.id()).ticket()).isEqualTo(ready.ticket());
        var claims=tickets.verify(ready.ticket(),"u","k",request); tickets.requireFresh(claims);
        assertThat(claims.expiresAt()-System.currentTimeMillis()).isBetween(1L,10000L);
        assertThatThrownBy(()->tickets.verify(ready.ticket(),"other","k",request)).hasMessage("INVALID_ADMISSION");
        assertThatThrownBy(()->tickets.verify(ready.ticket(),"u","other",request)).hasMessage("INVALID_ADMISSION");
        assertThatThrownBy(()->waiting.get("other",joined.id())).hasMessage("ADMISSION_NOT_FOUND");
    }
    @Test void expiredReadyCanRejoinButDoesNotBecomeAStockHold() {
        var joined=waiting.join("u","k",request); waiting.get("u",joined.id());
        redis.opsForHash().put(prefix+"waiting:item:"+joined.id(),"ready","0");
        redis.opsForZSet().add(prefix+"waiting:ready",joined.id(),0);
        assertThat(waiting.get("u",joined.id()).state()).isEqualTo("EXPIRED");
        assertThat(waiting.join("u","k",request).state()).isEqualTo("WAITING");
    }
    @Test void absentBrowserIsSkippedAndCapacityDoesNotExceedReadyLimit() {
        var abandoned=waiting.join("gone","k",request);
        redis.opsForZSet().add(prefix+"waiting:live",abandoned.id(),0);
        var next=waiting.join("next","k",request);
        assertThat(waiting.get("next",next.id()).state()).isEqualTo("READY");
        assertThat(waiting.get("gone",abandoned.id()).state()).isEqualTo("EXPIRED");
        for(int i=0;i<4;i++) {
            redis.delete(prefix+"waiting:rate"); // Isolate outstanding-ticket limit from rate limit.
            var candidate=waiting.join("u"+i,"k",request); waiting.get("u"+i,candidate.id());
        }
        assertThat(redis.opsForZSet().zCard(prefix+"waiting:ready")).isEqualTo(2);
    }
    @Test void preopenHeldSoldAndMissingProjectionHaveDifferentResults() {
        redis.opsForValue().set(prefix+"catalog:"+request.saleId(),(System.currentTimeMillis()+60000)+":AVAILABLE");
        assertThatThrownBy(()->waiting.join("u","k",request)).hasMessage("SALE_NOT_OPEN");
        redis.opsForValue().set(prefix+"catalog:"+request.saleId(),"0:FULLY_HELD");
        var joined=waiting.join("u","k",request);
        assertThat(waiting.get("u",joined.id()).state()).isEqualTo("WAITING_FOR_INVENTORY_RETURN");
        redis.opsForHash().put(prefix+"waiting:item:"+joined.id(),"next","0");
        redis.opsForValue().set(prefix+"catalog:"+request.saleId(),"0:SOLD_OUT");
        assertThat(waiting.get("u",joined.id()).state()).isEqualTo("SOLD_OUT");
        redis.delete(prefix+"catalog:"+request.saleId());
        assertThatThrownBy(()->waiting.join("new","k",request)).hasMessage("WAITING_UNAVAILABLE");
    }
    @Test void differentBasketCannotReuseWaitingKeyAndRateIsShared() {
        var first=waiting.join("u","k",request);
        var changed=new PurchaseRequest(request.saleId(),List.of(new PurchaseRequest.Item(request.items().getFirst().saleItemId(),2)));
        assertThatThrownBy(()->waiting.join("u","k",changed)).hasMessage("IDEMPOTENCY_KEY_REUSED");
        redis.opsForValue().set(prefix+"waiting:rate",""+(System.currentTimeMillis()+60000));
        assertThat(waiting.get("u",first.id()).state()).isEqualTo("WAITING");
        assertThat(redis.opsForZSet().zCard(prefix+"waiting:ready")).isZero();
    }
}
