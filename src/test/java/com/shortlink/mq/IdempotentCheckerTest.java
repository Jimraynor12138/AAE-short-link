package com.shortlink.mq;

import com.shortlink.config.ShortLinkProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消费幂等测试：首次通过、重复拦截、Redis 异常时按首次处理（宁可重复不可丢）
 */
@ExtendWith(MockitoExtension.class)
class IdempotentCheckerTest {

    private static final String VISIT_ID = "visit-1";
    private static final String KEY = "short-link:mq:consumed:visit-1";

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private IdempotentChecker checker;
    private ShortLinkProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ShortLinkProperties();
        checker = new IdempotentChecker(stringRedisTemplate, properties);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    /** TTL 取自配置，避免默认值调整后测试与实现脱节 */
    private Duration idempotentTtl() {
        return Duration.ofSeconds(properties.getMq().getIdempotentTtlSeconds());
    }

    @Test
    void firstConsumeReturnsTrue() {
        when(valueOperations.setIfAbsent(KEY, "1", idempotentTtl())).thenReturn(true);
        assertTrue(checker.tryConsume(VISIT_ID));
    }

    @Test
    void duplicateConsumeReturnsFalse() {
        when(valueOperations.setIfAbsent(KEY, "1", idempotentTtl())).thenReturn(false);
        assertFalse(checker.tryConsume(VISIT_ID));
    }

    @Test
    void redisFailureTreatsAsFirstTime() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));
        assertTrue(checker.tryConsume(VISIT_ID));
    }

    @Test
    void releaseDeletesKey() {
        checker.release(VISIT_ID);
        verify(stringRedisTemplate).delete(KEY);
    }

    @Test
    void blankVisitIdIsProcessedWithoutIdempotencyCheck() {
        // 没有消息 ID 时如果用固定 key，会导致这类消息只有第一条能通过、其余被误判为重复
        assertTrue(checker.tryConsume(null));
        assertTrue(checker.tryConsume("   "));
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    void releaseIgnoresBlankVisitId() {
        checker.release(null);
        checker.release("");
        verify(stringRedisTemplate, never()).delete(anyString());
    }
}
