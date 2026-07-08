package com.limitedgoodsreservation.waitingroom.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

class RedisWaitingRoomStoreTest {

    @Test
    void createsProductScopedRedisKeys() {
        assertThat(RedisWaitingRoomStore.waitingSequenceKey(1L)).isEqualTo("waiting:sequence:1");
        assertThat(RedisWaitingRoomStore.waitingQueueKey(1L)).isEqualTo("waiting:queue:1");
        assertThat(RedisWaitingRoomStore.waitingUserKey(1L, 1001L)).isEqualTo("waiting:user:1:1001");
        assertThat(RedisWaitingRoomStore.activeTokenKey(1L, 1001L)).isEqualTo("active-token:1:1001");
        assertThat(RedisWaitingRoomStore.activeTokenIndexKey(1L)).isEqualTo("active-token:index:1");
    }

    @Test
    void admitsOnlyConfiguredBatchSizeWhenCapacityHasEnoughRoom() {
        RedisFixture fixture = new RedisFixture();
        RedisWaitingRoomStore store = new RedisWaitingRoomStore(fixture.redisTemplate);
        when(fixture.zSetOperations.zCard(RedisWaitingRoomStore.activeTokenIndexKey(1L)))
                .thenReturn(0L, 3L);
        when(fixture.zSetOperations.zCard(RedisWaitingRoomStore.waitingQueueKey(1L)))
                .thenReturn(7L);
        when(fixture.zSetOperations.popMin(RedisWaitingRoomStore.waitingQueueKey(1L)))
                .thenReturn(tuple("1001"), tuple("1002"), tuple("1003"));

        AdmissionResult result = store.admit(1L, 3, 100, Duration.ofSeconds(60));

        assertThat(result.issuedCount()).isEqualTo(3);
        assertThat(result.queueSize()).isEqualTo(7);
        assertThat(result.activeTokenCount()).isEqualTo(3);
        verify(fixture.zSetOperations, times(3)).popMin(RedisWaitingRoomStore.waitingQueueKey(1L));
        verify(fixture.valueOperations).set(
                RedisWaitingRoomStore.activeTokenKey(1L, 1001L),
                "ACTIVE",
                Duration.ofSeconds(60)
        );
        verify(fixture.redisTemplate).delete(RedisWaitingRoomStore.waitingUserKey(1L, 1001L));
    }

    @Test
    void admitsOnlyRemainingCapacityWhenActiveTokensAlreadyExist() {
        RedisFixture fixture = new RedisFixture();
        RedisWaitingRoomStore store = new RedisWaitingRoomStore(fixture.redisTemplate);
        when(fixture.zSetOperations.zCard(RedisWaitingRoomStore.activeTokenIndexKey(1L)))
                .thenReturn(4L, 5L);
        when(fixture.zSetOperations.zCard(RedisWaitingRoomStore.waitingQueueKey(1L)))
                .thenReturn(9L);
        when(fixture.zSetOperations.popMin(RedisWaitingRoomStore.waitingQueueKey(1L)))
                .thenReturn(tuple("1001"));

        AdmissionResult result = store.admit(1L, 20, 5, Duration.ofSeconds(60));

        assertThat(result.issuedCount()).isEqualTo(1);
        assertThat(result.activeTokenCount()).isEqualTo(5);
        verify(fixture.zSetOperations, times(1)).popMin(RedisWaitingRoomStore.waitingQueueKey(1L));
    }

    @Test
    void doesNotAdmitWhenActiveCapacityIsFull() {
        RedisFixture fixture = new RedisFixture();
        RedisWaitingRoomStore store = new RedisWaitingRoomStore(fixture.redisTemplate);
        when(fixture.zSetOperations.zCard(RedisWaitingRoomStore.activeTokenIndexKey(1L)))
                .thenReturn(5L, 5L);
        when(fixture.zSetOperations.zCard(RedisWaitingRoomStore.waitingQueueKey(1L)))
                .thenReturn(10L);

        AdmissionResult result = store.admit(1L, 20, 5, Duration.ofSeconds(60));

        assertThat(result.issuedCount()).isZero();
        assertThat(result.queueSize()).isEqualTo(10);
        assertThat(result.activeTokenCount()).isEqualTo(5);
        verify(fixture.zSetOperations, never()).popMin(RedisWaitingRoomStore.waitingQueueKey(1L));
    }

    @Test
    void cleansExpiredActiveTokenIndexBeforeCountingCapacity() {
        RedisFixture fixture = new RedisFixture();
        RedisWaitingRoomStore store = new RedisWaitingRoomStore(fixture.redisTemplate);
        when(fixture.zSetOperations.zCard(RedisWaitingRoomStore.activeTokenIndexKey(1L)))
                .thenReturn(2L, 3L);
        when(fixture.zSetOperations.zCard(RedisWaitingRoomStore.waitingQueueKey(1L)))
                .thenReturn(9L);
        when(fixture.zSetOperations.popMin(RedisWaitingRoomStore.waitingQueueKey(1L)))
                .thenReturn(tuple("1001"));

        AdmissionResult result = store.admit(1L, 10, 3, Duration.ofSeconds(60));

        assertThat(result.issuedCount()).isEqualTo(1);
        verify(fixture.zSetOperations).removeRangeByScore(
                eq(RedisWaitingRoomStore.activeTokenIndexKey(1L)),
                eq(0.0),
                anyDouble()
        );
    }

    @Test
    void consumesActiveTokenAndRemovesCapacityIndex() {
        RedisFixture fixture = new RedisFixture();
        RedisWaitingRoomStore store = new RedisWaitingRoomStore(fixture.redisTemplate);
        when(fixture.redisTemplate.delete(RedisWaitingRoomStore.activeTokenKey(1L, 1001L)))
                .thenReturn(true);

        boolean consumed = store.consumeActiveToken(1L, 1001L);

        assertThat(consumed).isTrue();
        verify(fixture.redisTemplate).delete(RedisWaitingRoomStore.activeTokenKey(1L, 1001L));
        verify(fixture.zSetOperations).remove(RedisWaitingRoomStore.activeTokenIndexKey(1L), "1001");
    }

    @Test
    void restoresActiveTokenAndCapacityIndex() {
        RedisFixture fixture = new RedisFixture();
        RedisWaitingRoomStore store = new RedisWaitingRoomStore(fixture.redisTemplate);

        store.restoreActiveToken(1L, 1001L, Duration.ofSeconds(60));

        verify(fixture.valueOperations).set(
                RedisWaitingRoomStore.activeTokenKey(1L, 1001L),
                "ACTIVE",
                Duration.ofSeconds(60)
        );
        verify(fixture.zSetOperations).add(
                eq(RedisWaitingRoomStore.activeTokenIndexKey(1L)),
                eq("1001"),
                anyDouble()
        );
    }


    @Test
    void duplicateEnterDoesNotCreateAnotherQueueMember() {
        RedisFixture fixture = new RedisFixture();
        RedisWaitingRoomStore store = new RedisWaitingRoomStore(fixture.redisTemplate);
        when(fixture.redisTemplate.hasKey(RedisWaitingRoomStore.activeTokenKey(1L, 1001L)))
                .thenReturn(false);
        when(fixture.redisTemplate.hasKey(RedisWaitingRoomStore.waitingUserKey(1L, 1001L)))
                .thenReturn(true);
        when(fixture.zSetOperations.score(RedisWaitingRoomStore.waitingQueueKey(1L), "1001"))
                .thenReturn(1.0);
        when(fixture.zSetOperations.rank(RedisWaitingRoomStore.waitingQueueKey(1L), "1001"))
                .thenReturn(0L);
        when(fixture.zSetOperations.zCard(RedisWaitingRoomStore.waitingQueueKey(1L)))
                .thenReturn(1L);

        WaitingRoomEntry entry = store.enter(1L, 1001L);

        assertThat(entry.duplicate()).isTrue();
        assertThat(entry.rank()).isEqualTo(1L);
        verify(fixture.valueOperations, never()).increment(RedisWaitingRoomStore.waitingSequenceKey(1L));
        verify(fixture.zSetOperations, never()).add(RedisWaitingRoomStore.waitingQueueKey(1L), "1001", 1.0);
    }

    private static ZSetOperations.TypedTuple<String> tuple(String value) {
        return new ZSetOperations.TypedTuple<>() {
            @Override
            public String getValue() {
                return value;
            }

            @Override
            public Double getScore() {
                return 1.0;
            }

            @Override
            public int compareTo(ZSetOperations.TypedTuple<String> other) {
                return getScore().compareTo(other.getScore());
            }
        };
    }

    private static class RedisFixture {

        private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        private final ValueOperations<String, String> valueOperations = mock();
        private final ZSetOperations<String, String> zSetOperations = mock();

        private RedisFixture() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        }
    }
}
