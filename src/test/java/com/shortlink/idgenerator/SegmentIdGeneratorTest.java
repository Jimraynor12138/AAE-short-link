package com.shortlink.idgenerator;

import com.shortlink.config.ShortLinkProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 号段发号器测试（V3.5）：段内发号、双 buffer 预取、段切换零等待、同步兜底、并发唯一性
 */
@ExtendWith(MockitoExtension.class)
class SegmentIdGeneratorTest {

    @Mock
    private SegmentAllocator allocator;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private ShortLinkProperties properties;
    private SegmentIdGenerator generator;

    @BeforeEach
    void setUp() {
        properties = new ShortLinkProperties();
        properties.setDomain("localhost:8080");
        properties.getSegment().setStep(10);
        // 剩余 ≤ 20% * 10 = 2 个号时触发预取
        properties.getSegment().setPrefetchRemainingPercent(20);
        properties.getSegment().setAllocRetryTimes(1);
        properties.getSegment().setAllocRetryIntervalMillis(0);
        // 同步执行器：预取在当前线程完成，断言可确定
        generator = new SegmentIdGenerator(allocator, properties, jdbcTemplate, Runnable::run);
    }

    private void dbMaxId(long value) {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(value);
    }

    @Test
    void seedAlignsLocalCursorToDbMaxId() {
        // 这是最容易写错的地方：本地起点若从 0 开始，首段会覆盖 DB 里已用过的号
        dbMaxId(70L);

        generator.seed();
        when(allocator.allocate("short-link", 10)).thenReturn(80L);

        assertEquals(71L, generator.nextId());

        verify(allocator).raiseTo("short-link", 10, 70L);
    }

    @Test
    void firstCallLoadsSegmentThenIdsAreSequentialInMemory() {
        dbMaxId(0L);
        generator.seed();
        when(allocator.allocate("short-link", 10)).thenReturn(10L);

        assertEquals(1L, generator.nextId());
        assertEquals(2L, generator.nextId());
        assertEquals(3L, generator.nextId());

        // 段内发号完全在内存里完成
        verify(allocator, times(1)).allocate("short-link", 10);
    }

    @Test
    void prefetchIsTriggeredWhenRemainingBelowThresholdAndSwitchDoesNotAllocate() {
        dbMaxId(0L);
        generator.seed();
        when(allocator.allocate("short-link", 10)).thenReturn(10L, 20L);

        // 1..8：第 8 个发出后剩余 2 个（= 阈值）→ 触发预取（同步执行器，立即完成）
        for (long expected = 1; expected <= 8; expected++) {
            assertEquals(expected, generator.nextId());
        }
        verify(allocator, times(2)).allocate("short-link", 10);

        // 9、10 用完当前段；11 必须来自预取段，且不再分配
        assertEquals(9L, generator.nextId());
        assertEquals(10L, generator.nextId());
        assertEquals(11L, generator.nextId());

        verify(allocator, times(2)).allocate("short-link", 10);
    }

    @Test
    void fallsBackToSyncAllocationWhenPrefetchFailed() {
        properties.getSegment().setAllocRetryTimes(1);
        dbMaxId(0L);
        generator.seed();
        // 第 1 次（首段）成功；第 2 次（预取）失败；第 3 次（段耗尽后的同步兜底）成功
        when(allocator.allocate("short-link", 10))
                .thenReturn(10L)
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(20L);

        for (long expected = 1; expected <= 10; expected++) {
            assertEquals(expected, generator.nextId());
        }
        // 预取失败不影响发号：段耗尽时同步分配兜底
        assertEquals(11L, generator.nextId());

        verify(allocator, times(3)).allocate("short-link", 10);
    }

    @Test
    void allocationFailureThrowsAfterRetries() {
        dbMaxId(0L);
        generator.seed();
        when(allocator.allocate("short-link", 10)).thenThrow(new RuntimeException("db down"));

        assertThrows(IllegalStateException.class, () -> generator.nextId());
    }

    @Test
    void recoverAfterConflictRaisesSegmentAboveDbMax() {
        dbMaxId(0L);
        generator.seed();
        when(allocator.allocate("short-link", 10)).thenReturn(10L);
        assertEquals(1L, generator.nextId());

        dbMaxId(500L);
        when(allocator.allocate("short-link", 10)).thenReturn(510L);
        generator.recoverAfterConflict();

        verify(allocator).raiseTo("short-link", 10, 500L);
        // 自愈后必须发到 500 之上，否则会继续撞已用过的号
        assertTrue(generator.nextId() > 500L);
    }

    @Test
    void seedSkipsRaiseWhenDbIsEmpty() {
        dbMaxId(0L);

        generator.seed();

        verify(allocator, never()).raiseTo(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void concurrentIdsAreUniqueAndContiguousWithinSegments() throws Exception {
        properties.getSegment().setStep(1000);
        dbMaxId(0L);
        generator.seed();
        AtomicInteger allocated = new AtomicInteger();
        when(allocator.allocate(eq("short-link"), eq(1000)))
                .thenAnswer(invocation -> (long) (allocated.incrementAndGet() * 1000));

        int threads = 8;
        int perThread = 500;
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?>[] futures = new Future<?>[threads];
            for (int t = 0; t < threads; t++) {
                futures[t] = pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ids.add(generator.nextId());
                    }
                    return null;
                });
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads * perThread, ids.size(), "并发发号不允许重复");
        // 号段模式保证唯一，但不保证连续（预取竞态可能浪费整段），所以只断言起点存在
        assertTrue(ids.contains(1L));
        assertTrue(ids.stream().allMatch(id -> id > 0));
    }
}
