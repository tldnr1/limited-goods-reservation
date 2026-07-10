package com.limitedgoodsreservation.reservation.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.limitedgoodsreservation.waitingroom.service.WaitingRoomProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisReservationFrontGateTest {

    @Test
    void createsReservationScopedRedisKeys() {
        assertThat(RedisReservationFrontGate.idempotencyKey("request-1"))
                .isEqualTo("reservation:idem:request-1");
        assertThat(RedisReservationFrontGate.userReservationKey(1L, 1001L))
                .isEqualTo("reservation:user:1:1001");
    }

    @Test
    void mapsGateScriptResultToDecisionAndUsesExpectedKeys() {
        StubRedisTemplate redisTemplate = new StubRedisTemplate(RedisFrontGateDecision.ACCEPTED.code());
        WaitingRoomProperties waitingRoomProperties = new WaitingRoomProperties();
        waitingRoomProperties.setEnabled(true);
        RedisReservationFrontGate frontGate = new RedisReservationFrontGate(redisTemplate, waitingRoomProperties);

        RedisFrontGateDecision decision = frontGate.enter(1001L, 1L, "request-1");

        assertThat(decision).isEqualTo(RedisFrontGateDecision.ACCEPTED);
        assertThat(redisTemplate.lastKeys).containsExactly(
                "active-token:1:1001",
                "active-token:index:1",
                "stock:available:1",
                "reservation:idem:request-1",
                "reservation:user:1:1001"
        );
        assertThat(redisTemplate.lastArgs).containsExactly("1", "1001", "PROCESSING:1:1001", "30000");
    }

    @Test
    void disablesActiveTokenRequirementWhenWaitingRoomIsDisabled() {
        StubRedisTemplate redisTemplate = new StubRedisTemplate(RedisFrontGateDecision.ACCEPTED.code());
        WaitingRoomProperties waitingRoomProperties = new WaitingRoomProperties();
        waitingRoomProperties.setEnabled(false);
        RedisReservationFrontGate frontGate = new RedisReservationFrontGate(redisTemplate, waitingRoomProperties);

        frontGate.enter(1001L, 1L, "request-1");

        assertThat(redisTemplate.lastArgs[0]).isEqualTo("0");
    }

    private static class StubRedisTemplate extends StringRedisTemplate {

        private final Long result;
        private List<String> lastKeys;
        private Object[] lastArgs;

        private StubRedisTemplate(Long result) {
            this.result = result;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            lastKeys = keys;
            lastArgs = args;
            return (T) result;
        }
    }
}
