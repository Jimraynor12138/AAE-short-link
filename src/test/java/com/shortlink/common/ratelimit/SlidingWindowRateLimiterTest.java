package com.shortlink.common.ratelimit;

import com.shortlink.config.ShortLinkProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 滑动窗口限流器测试（V3.3）：阈值、窗口滑动、维度隔离、开关、并发安全
 */
class SlidingWindowRateLimiterTest {

    /** 可手动推进的时钟，避免测试真的 sleep */
    private static class MutableClock extends Clock {
        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void plusSeconds(long seconds) {
            millis += seconds * 1000;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }

    private ShortLinkProperties properties;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        properties = new ShortLinkProperties();
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setWindowSeconds(60);
        properties.getRateLimit().setWriteLimitPerWindow(3);
        clock = new MutableClock(1_000_000L);
    }

    private SlidingWindowRateLimiter limiter() {
        return new SlidingWindowRateLimiter(properties, clock);
    }

    @Test
    void allowsUpToLimitThenRejects() {
        SlidingWindowRateLimiter limiter = limiter();

        assertTrue(limiter.tryAcquire("ip:1.1.1.1"));
        assertTrue(limiter.tryAcquire("ip:1.1.1.1"));
        assertTrue(limiter.tryAcquire("ip:1.1.1.1"));
        assertFalse(limiter.tryAcquire("ip:1.1.1.1"));
    }

    @Test
    void windowSlidesAfterOldRequestsExpire() {
        SlidingWindowRateLimiter limiter = limiter();
        limiter.tryAcquire("ip:1.1.1.1");
        limiter.tryAcquire("ip:1.1.1.1");
        limiter.tryAcquire("ip:1.1.1.1");
        assertFalse(limiter.tryAcquire("ip:1.1.1.1"));

        // 时间推进超过窗口 → 老记录被淘汰，重新放行
        clock.plusSeconds(61);

        assertTrue(limiter.tryAcquire("ip:1.1.1.1"));
    }

    @Test
    void differentKeysAreIsolated() {
        SlidingWindowRateLimiter limiter = limiter();
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("ip:1.1.1.1"));
        }

        assertFalse(limiter.tryAcquire("ip:1.1.1.1"));
        // 另一个客户端不受影响
        assertTrue(limiter.tryAcquire("ip:2.2.2.2"));
    }

    @Test
    void disabledRateLimitAllowsEverything() {
        properties.getRateLimit().setEnabled(false);
        SlidingWindowRateLimiter limiter = limiter();

        for (int i = 0; i < 100; i++) {
            assertTrue(limiter.tryAcquire("ip:1.1.1.1"));
        }
    }

    @Test
    void concurrentRequestsNeverExceedLimit() throws Exception {
        // 并发安全：8 线程各打 50 次，限流 100 → 恰好放行 100 次
        properties.getRateLimit().setWriteLimitPerWindow(100);
        SlidingWindowRateLimiter limiter = limiter();
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            Future<?>[] futures = new Future<?>[8];
            for (int t = 0; t < 8; t++) {
                futures[t] = pool.submit(() -> {
                    for (int i = 0; i < 50; i++) {
                        if (limiter.tryAcquire("ip:1.1.1.1")) {
                            allowed.incrementAndGet();
                        }
                    }
                });
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(100, allowed.get());
    }
}
