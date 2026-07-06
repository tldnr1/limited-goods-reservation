package com.limitedgoodsreservation.waitingroom.service;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

@Component
public class RedisWaitingRoomStore implements WaitingRoomStore {

    private static final String ACTIVE_TOKEN_VALUE = "ACTIVE";

    private final StringRedisTemplate redisTemplate;

    public RedisWaitingRoomStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public WaitingRoomEntry enter(Long productId, Long userId) {
        if (hasActiveToken(productId, userId)) {
            return activeEntry(productId, userId, true);
        }

        String member = member(userId);
        String queueKey = waitingQueueKey(productId);
        String userKey = waitingUserKey(productId, userId);

        if (Boolean.TRUE.equals(redisTemplate.hasKey(userKey))) {
            Double existingScore = redisTemplate.opsForZSet().score(queueKey, member);
            if (existingScore != null) {
                return waitingEntry(productId, userId, true);
            }
            redisTemplate.delete(userKey);
        }

        Long sequence = redisTemplate.opsForValue().increment(waitingSequenceKey(productId));
        if (sequence == null) {
            throw new IllegalStateException("Failed to create waiting sequence. productId=" + productId);
        }

        Boolean added = redisTemplate.opsForZSet().add(queueKey, member, sequence.doubleValue());
        if (!Boolean.TRUE.equals(added)) {
            return waitingEntry(productId, userId, true);
        }
        redisTemplate.opsForValue().set(userKey, String.valueOf(sequence));

        return waitingEntry(productId, userId, false);
    }

    @Override
    public WaitingRoomEntry status(Long productId, Long userId) {
        if (hasActiveToken(productId, userId)) {
            return activeEntry(productId, userId, false);
        }

        Double score = redisTemplate.opsForZSet().score(waitingQueueKey(productId), member(userId));
        if (score == null) {
            return notFoundEntry(productId, userId);
        }

        return waitingEntry(productId, userId, false);
    }

    @Override
    public AdmissionResult admit(Long productId, int batchSize, int activeCapacity, Duration tokenTtl) {
        String queueKey = waitingQueueKey(productId);
        String activeIndexKey = activeTokenIndexKey(productId);
        long nowMillis = System.currentTimeMillis();

        redisTemplate.opsForZSet().removeRangeByScore(activeIndexKey, 0, nowMillis);

        Long currentActiveCount = redisTemplate.opsForZSet().zCard(activeIndexKey);
        int activeCount = currentActiveCount == null ? 0 : currentActiveCount.intValue();
        int slots = Math.min(batchSize, Math.max(activeCapacity - activeCount, 0));
        int issued = 0;

        for (int i = 0; i < slots; i += 1) {
            ZSetOperations.TypedTuple<String> next = redisTemplate.opsForZSet().popMin(queueKey);
            if (next == null || next.getValue() == null) {
                break;
            }

            Long userId = Long.valueOf(next.getValue());
            redisTemplate.opsForValue().set(activeTokenKey(productId, userId), ACTIVE_TOKEN_VALUE, tokenTtl);
            redisTemplate.opsForZSet().add(activeIndexKey, member(userId), nowMillis + tokenTtl.toMillis());
            redisTemplate.delete(waitingUserKey(productId, userId));
            issued += 1;
        }

        return new AdmissionResult(issued, size(queueKey), activeTokenCount(productId));
    }

    @Override
    public boolean consumeActiveToken(Long productId, Long userId) {
        Boolean deleted = redisTemplate.delete(activeTokenKey(productId, userId));
        redisTemplate.opsForZSet().remove(activeTokenIndexKey(productId), member(userId));
        return Boolean.TRUE.equals(deleted);
    }

    private WaitingRoomEntry waitingEntry(Long productId, Long userId, boolean duplicate) {
        String queueKey = waitingQueueKey(productId);
        Long rank = redisTemplate.opsForZSet().rank(queueKey, member(userId));
        return new WaitingRoomEntry(
                productId,
                userId,
                WaitingRoomStatus.WAITING,
                rank == null ? null : rank + 1,
                size(queueKey),
                duplicate
        );
    }

    private WaitingRoomEntry activeEntry(Long productId, Long userId, boolean duplicate) {
        return new WaitingRoomEntry(
                productId,
                userId,
                WaitingRoomStatus.ACTIVE,
                null,
                size(waitingQueueKey(productId)),
                duplicate
        );
    }

    private WaitingRoomEntry notFoundEntry(Long productId, Long userId) {
        return new WaitingRoomEntry(
                productId,
                userId,
                WaitingRoomStatus.NOT_FOUND,
                null,
                size(waitingQueueKey(productId)),
                false
        );
    }

    private boolean hasActiveToken(Long productId, Long userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(activeTokenKey(productId, userId)));
    }

    private long activeTokenCount(Long productId) {
        Long count = redisTemplate.opsForZSet().zCard(activeTokenIndexKey(productId));
        return count == null ? 0 : count;
    }

    private long size(String key) {
        Long size = redisTemplate.opsForZSet().zCard(key);
        return size == null ? 0 : size;
    }

    private String member(Long userId) {
        return String.valueOf(userId);
    }

    public static String waitingSequenceKey(Long productId) {
        return "waiting:sequence:" + productId;
    }

    public static String waitingQueueKey(Long productId) {
        return "waiting:queue:" + productId;
    }

    public static String waitingUserKey(Long productId, Long userId) {
        return "waiting:user:" + productId + ":" + userId;
    }

    public static String activeTokenKey(Long productId, Long userId) {
        return "active-token:" + productId + ":" + userId;
    }

    public static String activeTokenIndexKey(Long productId) {
        return "active-token:index:" + productId;
    }
}
