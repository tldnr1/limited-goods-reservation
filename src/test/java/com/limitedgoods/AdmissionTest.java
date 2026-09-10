package com.limitedgoods;
import com.limitedgoods.admission.AdmissionGate;
import com.limitedgoods.config.ApiError;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class AdmissionTest {
    LettuceConnectionFactory connection;
    StringRedisTemplate redis;
    String prefix;
    AdmissionGate gate;
    @BeforeEach void setup() {
        connection=new LettuceConnectionFactory("localhost",6380);
        connection.afterPropertiesSet(); connection.start();
        redis=new StringRedisTemplate(connection);
        prefix="goods:test:"+UUID.randomUUID()+":";
        gate=new AdmissionGate(redis,true,prefix,4,new SimpleMeterRegistry(),0);
    }
    @AfterEach void cleanup() {
        redis.delete(List.of(prefix+"inflight",prefix+"purchase:rate"));
        connection.destroy();
    }
    @Test void luaAdmissionIsAtomicAcrossConcurrentClients() throws Exception {
        var barrier=new CyclicBarrier(12); List<String> tokens=new ArrayList<>();
        try(var pool=Executors.newFixedThreadPool(12)) {
            var futures=new ArrayList<Future<String>>();
            for(int i=0;i<12;i++) futures.add(pool.submit(()->{
                barrier.await(5,TimeUnit.SECONDS);
                try { return gate.enter(); } catch(ApiError e) { assertThat(e.status).isEqualTo(429); return null; }
            }));
            for(var f:futures) { var token=f.get(10,TimeUnit.SECONDS); if(token!=null) tokens.add(token); }
        }
        assertThat(tokens).hasSize(4);
        tokens.forEach(gate::leave);
        assertThat(redis.opsForZSet().zCard(prefix+"inflight")).isZero();
    }
    @Test void expiredPermitIsRecoveredWithoutProcessCleanup() {
        redis.opsForZSet().add(prefix+"inflight","dead-process",0);
        var token=gate.enter();
        assertThat(redis.opsForZSet().score(prefix+"inflight","dead-process")).isNull();
        gate.leave(token);
    }
    @Test void releasingConcurrencyPermitDoesNotBypassRateBudget() {
        var limited=new AdmissionGate(redis,true,prefix,4,new SimpleMeterRegistry(),25);
        var token=limited.enter(); limited.leave(token);
        // Pin the next permitted time instead of relying on the test machine's execution speed.
        redis.opsForValue().set(prefix+"purchase:rate",""+(System.currentTimeMillis()+60000));
        assertThatThrownBy(limited::enter).hasMessage("PURCHASE_BUSY");
        assertThat(redis.opsForZSet().zCard(prefix+"inflight")).isZero();
        redis.delete(prefix+"purchase:rate");
        limited.leave(limited.enter());
    }
    @Test void negativeCacheIsBoundedAndAdvisory() {
        UUID item=UUID.randomUUID();
        gate.rememberUnavailable(item);
        assertThat(gate.unavailable(item)).isTrue();
        assertThat(redis.getExpire(prefix+"empty:"+item,TimeUnit.MILLISECONDS)).isBetween(1L,1000L);
        redis.delete(prefix+"empty:"+item);
        assertThat(gate.unavailable(item)).isFalse();
    }
    @Test void redisFailureRejectsNewAdmissionWithoutMaskingCommittedSuccess() {
        var broken=mock(StringRedisTemplate.class);
        when(broken.execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(Object[].class)))
            .thenThrow(new RedisConnectionFailureException("offline"));
        var unavailable=new AdmissionGate(broken,true,prefix,4,new SimpleMeterRegistry(),0);
        assertThatThrownBy(unavailable::enter).isInstanceOf(ApiError.class).hasMessage("ADMISSION_UNAVAILABLE");
    }
}
