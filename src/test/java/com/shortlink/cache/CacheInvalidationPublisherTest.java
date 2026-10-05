package com.shortlink.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 本地缓存失效广播发布者测试（V3.3）
 */
@ExtendWith(MockitoExtension.class)
class CacheInvalidationPublisherTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private CacheInvalidationPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new CacheInvalidationPublisher(stringRedisTemplate);
    }

    @Test
    void publishesCodeToInvalidationChannel() {
        publisher.publish("abc123");

        verify(stringRedisTemplate).convertAndSend(CacheInvalidationPublisher.CHANNEL, "abc123");
    }

    @Test
    void publishFailureIsSwallowed() {
        // Redis 故障时广播必然失败：不能阻断"改库 + 删缓存"的主流程，交给 L1 TTL 兜底
        doThrow(new RuntimeException("redis down"))
                .when(stringRedisTemplate).convertAndSend(CacheInvalidationPublisher.CHANNEL, "abc123");

        assertDoesNotThrow(() -> publisher.publish("abc123"));
    }
}
