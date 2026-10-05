package com.shortlink.cache;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缓存重建锁测试（V3.2）：抢锁、重复抢锁、fail-open、释放
 */
@ExtendWith(MockitoExtension.class)
class CacheRebuildLockTest {

    private static final String LOCK_KEY = "short-link:lock:rebuild:localhost:8080:abc123";

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private CacheRebuildLock rebuildLock;

    @BeforeEach
    void setUp() {
        ShortLinkProperties properties = new ShortLinkProperties();
        properties.setDomain("localhost:8080");
        rebuildLock = new CacheRebuildLock(stringRedisTemplate, properties);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void tryLockSucceedsWhenSetNxReturnsTrue() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        assertTrue(rebuildLock.tryLock("abc123"));
    }

    @Test
    void tryLockFailsWhenLockHeldByOthers() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertFalse(rebuildLock.tryLock("abc123"));
    }

    @Test
    void tryLockIsFailOpenWhenRedisDown() {
        // 锁不可用时必须放行：宁可多一个请求回源，也不能让所有请求都卡在等待上
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));
        assertTrue(rebuildLock.tryLock("abc123"));
    }

    @Test
    void unlockDeletesLockKey() {
        rebuildLock.unlock("abc123");
        verify(stringRedisTemplate).delete(LOCK_KEY);
    }

    @Test
    void unlockIgnoresRedisFailure() {
        when(stringRedisTemplate.delete(LOCK_KEY)).thenThrow(new RuntimeException("redis down"));
        rebuildLock.unlock("abc123");
    }
}
