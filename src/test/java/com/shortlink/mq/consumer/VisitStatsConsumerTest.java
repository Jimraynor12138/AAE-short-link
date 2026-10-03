package com.shortlink.mq.consumer;

import com.shortlink.mq.IdempotentChecker;
import com.shortlink.mq.message.VisitMessage;
import com.shortlink.service.StatsService;
import com.shortlink.service.StatsSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消费者测试：正常处理、重复跳过、严格写入抛出（触发重试/死信）、先标记脏再写统计
 */
@ExtendWith(MockitoExtension.class)
class VisitStatsConsumerTest {

    private static final LocalDate VISIT_DATE = LocalDate.of(2026, 10, 2);
    private static final String CODE = "abc123";

    @Mock
    private IdempotentChecker idempotentChecker;
    @Mock
    private StatsService statsService;
    @Mock
    private StatsSnapshotService statsSnapshotService;

    private VisitStatsConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new VisitStatsConsumer(idempotentChecker, statsService, statsSnapshotService);
    }

    private VisitMessage message() {
        VisitMessage message = new VisitMessage();
        message.setVisitId("visit-1");
        message.setCode(CODE);
        message.setClientIp("1.2.3.4");
        message.setUserAgent("Mozilla/5.0 (iPhone)");
        message.setReferer("https://www.baidu.com/s?wd=1");
        // 消息发生时间决定统计归属日期（跨零点场景）
        message.setVisitTime(LocalDateTime.of(2026, 10, 2, 23, 59, 59));
        return message;
    }

    @Test
    void consumesWithStrictWriteAndMessageDate() {
        when(idempotentChecker.tryConsume("visit-1")).thenReturn(true);

        consumer.onVisit(message());

        verify(statsService).recordVisitStrict(CODE, "1.2.3.4", "Mozilla/5.0 (iPhone)",
                "https://www.baidu.com/s?wd=1", VISIT_DATE);
        verify(statsSnapshotService).markDirty(CODE, VISIT_DATE);
    }

    @Test
    void marksDirtyBeforeWritingStats() {
        // 顺序保证：先标记脏再写统计，重试时不会重复累加 PV
        when(idempotentChecker.tryConsume("visit-1")).thenReturn(true);

        consumer.onVisit(message());

        InOrder inOrder = inOrder(statsSnapshotService, statsService);
        inOrder.verify(statsSnapshotService).markDirty(CODE, VISIT_DATE);
        inOrder.verify(statsService).recordVisitStrict(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void skipsDuplicateMessage() {
        when(idempotentChecker.tryConsume("visit-1")).thenReturn(false);

        consumer.onVisit(message());

        verify(statsService, never()).recordVisitStrict(anyString(), anyString(), anyString(), any(), any());
        verify(statsSnapshotService, never()).markDirty(anyString(), any());
    }

    @Test
    void releasesIdempotentKeyAndThrowsWhenStatsWriteFails() {
        // 统计写入失败必须抛出让 Spring AMQP 重试（这是修复前失效的路径）
        when(idempotentChecker.tryConsume("visit-1")).thenReturn(true);
        doThrow(new RuntimeException("redis down")).when(statsService)
                .recordVisitStrict(anyString(), anyString(), anyString(), any(), any());

        assertThrows(RuntimeException.class, () -> consumer.onVisit(message()));

        verify(idempotentChecker).release("visit-1");
    }

    @Test
    void nullMessageIsIgnoredWithoutException() {
        // payload 为空导致转换结果为空时，重投也无法处理，直接丢弃且不抛异常
        consumer.onVisit(null);
        verify(statsService, never()).recordVisitStrict(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void fallsBackToTodayWhenMessageHasNoTime() {
        when(idempotentChecker.tryConsume("visit-1")).thenReturn(true);
        VisitMessage message = message();
        message.setVisitTime(null);

        consumer.onVisit(message);

        verify(statsSnapshotService).markDirty(CODE, LocalDate.now());
    }
}
