package com.limitedgoodsreservation.reservation.gate;

import com.limitedgoodsreservation.stock.strategy.RedisLuaStockStrategy;
import com.limitedgoodsreservation.waitingroom.service.RedisWaitingRoomStore;
import com.limitedgoodsreservation.waitingroom.service.WaitingRoomProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisReservationFrontGate {

    private static final long PROCESSING_TTL_MILLIS = 30_000L;
    private static final String PROCESSING_PREFIX = "PROCESSING:";
    private static final String RESERVED_PREFIX = "RESERVED:";
    private static final Long COMPENSATED = 1L;

    private static final DefaultRedisScript<Long> GATE_SCRIPT = new DefaultRedisScript<>("""
            local idempotency = redis.call('GET', KEYS[4])
            if idempotency then
                if string.sub(idempotency, 1, 9) == 'RESERVED:' then
                    return -3
                end
                return -2
            end

            if ARGV[1] == '1' and redis.call('EXISTS', KEYS[1]) == 0 then
                return -1
            end

            if redis.call('EXISTS', KEYS[5]) == 1 then
                return -4
            end

            local available = redis.call('GET', KEYS[3])
            if not available then
                return -6
            end
            available = tonumber(available)
            if available <= 0 then
                return -5
            end

            if ARGV[1] == '1' then
                redis.call('DEL', KEYS[1])
                redis.call('ZREM', KEYS[2], ARGV[2])
            end
            redis.call('DECR', KEYS[3])
            redis.call('PSETEX', KEYS[4], ARGV[4], ARGV[3])
            redis.call('PSETEX', KEYS[5], ARGV[4], ARGV[3])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> FINALIZE_SCRIPT = new DefaultRedisScript<>("""
            local finalized = 0
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                redis.call('SET', KEYS[1], ARGV[2])
                finalized = 1
            end
            if redis.call('GET', KEYS[2]) == ARGV[1] then
                redis.call('SET', KEYS[2], ARGV[2])
                finalized = 1
            end
            return finalized
            """, Long.class);

    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT = new DefaultRedisScript<>("""
            local idem = redis.call('GET', KEYS[2])
            local user = redis.call('GET', KEYS[3])
            if idem == ARGV[1] or user == ARGV[1] then
                redis.call('INCR', KEYS[1])
                redis.call('DEL', KEYS[2])
                redis.call('DEL', KEYS[3])
                return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final WaitingRoomProperties waitingRoomProperties;

    public RedisReservationFrontGate(
            StringRedisTemplate redisTemplate,
            WaitingRoomProperties waitingRoomProperties
    ) {
        this.redisTemplate = redisTemplate;
        this.waitingRoomProperties = waitingRoomProperties;
    }

    public RedisFrontGateDecision enter(Long userId, Long productId, String idempotencyKey) {
        Long result = redisTemplate.execute(
                GATE_SCRIPT,
                List.of(
                        RedisWaitingRoomStore.activeTokenKey(productId, userId),
                        RedisWaitingRoomStore.activeTokenIndexKey(productId),
                        RedisLuaStockStrategy.stockKey(productId),
                        idempotencyKey(idempotencyKey),
                        userReservationKey(productId, userId)
                ),
                waitingRoomProperties.isEnabled() ? "1" : "0",
                String.valueOf(userId),
                processingValue(productId, userId),
                String.valueOf(PROCESSING_TTL_MILLIS)
        );
        if (result == null) {
            throw new IllegalStateException("Redis front-gate script returned null.");
        }
        return RedisFrontGateDecision.fromCode(result);
    }

    public boolean finalizeReservation(Long userId, Long productId, String idempotencyKey, Long reservationId) {
        Long result = redisTemplate.execute(
                FINALIZE_SCRIPT,
                List.of(idempotencyKey(idempotencyKey), userReservationKey(productId, userId)),
                processingValue(productId, userId),
                reservedValue(reservationId, productId, userId)
        );
        return result != null && result > 0;
    }

    public boolean compensate(Long userId, Long productId, String idempotencyKey) {
        Long result = redisTemplate.execute(
                COMPENSATE_SCRIPT,
                List.of(
                        RedisLuaStockStrategy.stockKey(productId),
                        idempotencyKey(idempotencyKey),
                        userReservationKey(productId, userId)
                ),
                processingValue(productId, userId)
        );
        return COMPENSATED.equals(result);
    }

    public static String idempotencyKey(String idempotencyKey) {
        return "reservation:idem:" + idempotencyKey;
    }

    public static String userReservationKey(Long productId, Long userId) {
        return "reservation:user:" + productId + ":" + userId;
    }

    private String processingValue(Long productId, Long userId) {
        return PROCESSING_PREFIX + productId + ":" + userId;
    }

    private String reservedValue(Long reservationId, Long productId, Long userId) {
        return RESERVED_PREFIX + reservationId + ":" + productId + ":" + userId;
    }
}
