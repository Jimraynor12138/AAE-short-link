package com.shortlink.service.impl;

import com.shortlink.cache.CacheKeyBuilder;
import com.shortlink.config.ShortLinkProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 访问统计单元测试（V1.3）：
 * PV 自增 / UV、IP 的 HyperLogLog 写入 / 首次写入设置 TTL / 异常隔离 / 查询聚合
 */
@ExtendWith(MockitoExtension.class)
class StatsServiceImplTest {

    private static final String TODAY = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    private static final String CODE = "abc123";

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private HyperLogLogOperations<String, String> hyperLogLogOperations;

    private StatsServiceImpl statsService;
    private ShortLinkProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ShortLinkProperties();
        properties.setDomain("localhost:8080");
        statsService = new StatsServiceImpl(stringRedisTemplate, properties);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(stringRedisTemplate.opsForHyperLogLog()).thenReturn(hyperLogLogOperations);
    }

    @Test
    void recordVisitCountsPvUvAndIp() {
        when(valueOperations.increment(CacheKeyBuilder.buildPvKey(TODAY, CODE))).thenReturn(5L);

        statsService.recordVisit(CODE, "1.2.3.4", "Mozilla/5.0");

        verify(valueOperations).increment(CacheKeyBuilder.buildPvKey(TODAY, CODE));
        verify(hyperLogLogOperations).add(eq(CacheKeyBuilder.buildUvKey(TODAY, CODE)), anyString());
        verify(hyperLogLogOperations).add(eq(CacheKeyBuilder.buildIpKey(TODAY, CODE)), eq("1.2.3.4"));
        // 非首次写入不重复设置 TTL（省 RTT）
        verify(stringRedisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void recordVisitSetsTtlOnFirstVisitOfDay() {
        when(valueOperations.increment(CacheKeyBuilder.buildPvKey(TODAY, CODE))).thenReturn(1L);

        statsService.recordVisit(CODE, "1.2.3.4", "Mozilla/5.0");

        verify(stringRedisTemplate).expire(CacheKeyBuilder.buildPvKey(TODAY, CODE), Duration.ofSeconds(604800));
        verify(stringRedisTemplate).expire(CacheKeyBuilder.buildUvKey(TODAY, CODE), Duration.ofSeconds(604800));
        verify(stringRedisTemplate).expire(CacheKeyBuilder.buildIpKey(TODAY, CODE), Duration.ofSeconds(604800));
    }

    @Test
    void recordVisitNeverThrows() {
        doThrow(new RuntimeException("redis down"))
                .when(valueOperations).increment(CacheKeyBuilder.buildPvKey(TODAY, CODE));
        assertDoesNotThrow(() -> statsService.recordVisit(CODE, "1.2.3.4", "Mozilla/5.0"));
    }

    @Test
    void queryStatsReadsPvAndHllSize() {
        when(valueOperations.get(CacheKeyBuilder.buildPvKey(TODAY, CODE))).thenReturn("42");
        when(hyperLogLogOperations.size(CacheKeyBuilder.buildUvKey(TODAY, CODE))).thenReturn(30L);
        when(hyperLogLogOperations.size(CacheKeyBuilder.buildIpKey(TODAY, CODE))).thenReturn(20L);

        var resp = statsService.queryStats(CODE, null);

        assertEquals(TODAY, resp.getDate());
        assertEquals(42L, resp.getPv());
        assertEquals(30L, resp.getUv());
        assertEquals(20L, resp.getIpCnt());
    }

    @Test
    void queryStatsTreatsMissingDataAsZero() {
        when(valueOperations.get(CacheKeyBuilder.buildPvKey(TODAY, CODE))).thenReturn(null);
        when(hyperLogLogOperations.size(CacheKeyBuilder.buildUvKey(TODAY, CODE))).thenReturn(0L);
        when(hyperLogLogOperations.size(CacheKeyBuilder.buildIpKey(TODAY, CODE))).thenReturn(0L);

        var resp = statsService.queryStats(CODE, "");

        assertEquals(0L, resp.getPv());
        assertEquals(0L, resp.getUv());
    }
}
