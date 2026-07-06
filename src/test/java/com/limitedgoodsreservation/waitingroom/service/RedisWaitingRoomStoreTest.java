package com.limitedgoodsreservation.waitingroom.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedisWaitingRoomStoreTest {

    @Test
    void createsProductScopedRedisKeys() {
        assertThat(RedisWaitingRoomStore.waitingSequenceKey(1L)).isEqualTo("waiting:sequence:1");
        assertThat(RedisWaitingRoomStore.waitingQueueKey(1L)).isEqualTo("waiting:queue:1");
        assertThat(RedisWaitingRoomStore.waitingUserKey(1L, 1001L)).isEqualTo("waiting:user:1:1001");
        assertThat(RedisWaitingRoomStore.activeTokenKey(1L, 1001L)).isEqualTo("active-token:1:1001");
        assertThat(RedisWaitingRoomStore.activeTokenIndexKey(1L)).isEqualTo("active-token:index:1");
    }
}
