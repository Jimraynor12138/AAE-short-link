package com.shortlink.service.impl;

import com.shortlink.cache.CacheKeyBuilder;
import com.shortlink.config.ShortLinkProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 访问统计单元测试：PV / UV / IP / 来源 / 设备，以及异常隔离与查询
 */
@ExtendWith(MockitoExtension.class)
class StatsServiceImplTest {

    private static final String TODAY = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    private static final String CODE = "abc123";
    private static final String PV_KEY = CacheKeyBuilder.buildPvKey(TODAY, CODE);
    private static final String UV_KEY = CacheKeyBuilder.buildUvKey(TODAY, CODE);
    private static final String IP_KEY = CacheKeyBuilder.buildIpKey(TODAY, CODE);
    private static final String REFERER_KEY = CacheKeyBuilder.buildRefererKey(TODAY, CODE);
    private static final String DEVICE_KEY = CacheKeyBuilder.buildDeviceKey(TODAY, CODE);

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private HyperLogLogOperations<String, String> hyperLogLogOperations;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private StatsServiceImpl statsService;

    @BeforeEach
    void setUp() {
        ShortLinkProperties properties = new ShortLinkProperties();
        properties.setDomain("localhost:8080");
        statsService = new StatsServiceImpl(stringRedisTemplate, properties);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(stringRedisTemplate.opsForHyperLogLog()).thenReturn(hyperLogLogOperations);
        lenient().when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    void recordVisitCountsAllDimensions() {
        when(valueOperations.increment(PV_KEY)).thenReturn(5L);

        statsService.recordVisit(CODE, "1.2.3.4", "Mozilla/5.0 (iPhone)", "https://www.baidu.com/s?wd=1");

        verify(valueOperations).increment(PV_KEY);
        verify(hyperLogLogOperations).add(eq(UV_KEY), anyString());
        verify(hyperLogLogOperations).add(eq(IP_KEY), eq("1.2.3.4"));
        verify(hashOperations).increment(REFERER_KEY, "www.baidu.com", 1L);
        verify(hashOperations).increment(DEVICE_KEY, "mobile", 1L);
        verify(stringRedisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void refererlessVisitCountsAsDirect() {
        when(valueOperations.increment(PV_KEY)).thenReturn(2L);

        statsService.recordVisit(CODE, "1.2.3.4", "Mozilla/5.0 (Windows NT 10.0)", null);

        verify(hashOperations).increment(REFERER_KEY, "direct", 1L);
        verify(hashOperations).increment(DEVICE_KEY, "pc", 1L);
    }

    @Test
    void firstVisitOfDaySetsTtlForAllStatKeys() {
        when(valueOperations.increment(PV_KEY)).thenReturn(1L);

        statsService.recordVisit(CODE, "1.2.3.4", "Mozilla/5.0", null);

        Duration ttl = Duration.ofSeconds(604800);
        verify(stringRedisTemplate).expire(PV_KEY, ttl);
        verify(stringRedisTemplate).expire(UV_KEY, ttl);
        verify(stringRedisTemplate).expire(IP_KEY, ttl);
        verify(stringRedisTemplate).expire(REFERER_KEY, ttl);
        verify(stringRedisTemplate).expire(DEVICE_KEY, ttl);
    }

    @Test
    void recordVisitNeverThrows() {
        // 跳转线程内使用：异常必须被吞掉，不能影响跳转
        doThrow(new RuntimeException("redis down")).when(valueOperations).increment(PV_KEY);
        assertDoesNotThrow(() -> statsService.recordVisit(CODE, "1.2.3.4", "UA", null));
    }

    @Test
    void recordVisitStrictThrowsOnFailure() {
        // 消费端使用：异常必须抛出，否则消息被 ack，重试/死信失效
        doThrow(new RuntimeException("redis down")).when(valueOperations).increment(PV_KEY);
        assertThrows(RuntimeException.class,
                () -> statsService.recordVisitStrict(CODE, "1.2.3.4", "UA", null, LocalDate.now()));
    }

    @Test
    void recordVisitStrictUsesGivenVisitDate() {
        // 跨零点：统计归入「访问发生当天」，而不是消费时刻
        LocalDate yesterday = LocalDate.now().minusDays(1);
        String pvKey = CacheKeyBuilder.buildPvKey(yesterday.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), CODE);
        when(valueOperations.increment(pvKey)).thenReturn(3L);

        statsService.recordVisitStrict(CODE, "1.2.3.4", "UA", null, yesterday);

        verify(valueOperations).increment(pvKey);
    }

    @Test
    void queryStatsReadsPvHllAndDimensions() {
        when(valueOperations.get(PV_KEY)).thenReturn("42");
        when(hyperLogLogOperations.size(UV_KEY)).thenReturn(30L);
        when(hyperLogLogOperations.size(IP_KEY)).thenReturn(20L);
        when(hashOperations.entries(REFERER_KEY)).thenReturn(Map.of("www.baidu.com", "25", "direct", "17"));
        when(hashOperations.entries(DEVICE_KEY)).thenReturn(Map.of("mobile", "30", "pc", "12"));

        var resp = statsService.queryStats(CODE, null);

        assertEquals(TODAY, resp.getDate());
        assertEquals(42L, resp.getPv());
        assertEquals(30L, resp.getUv());
        assertEquals(20L, resp.getIpCnt());
        assertEquals(25L, resp.getRefererStats().get("www.baidu.com"));
        assertEquals(30L, resp.getDeviceStats().get("mobile"));
    }

    @Test
    void queryStatsTreatsCorruptPvAsZero() {
        // Redis 里的值被外部改坏时不能让接口 500
        when(valueOperations.get(PV_KEY)).thenReturn("not-a-number");
        when(hyperLogLogOperations.size(UV_KEY)).thenReturn(0L);
        when(hyperLogLogOperations.size(IP_KEY)).thenReturn(0L);
        when(hashOperations.entries(REFERER_KEY)).thenReturn(Map.of());
        when(hashOperations.entries(DEVICE_KEY)).thenReturn(Map.of());

        assertEquals(0L, statsService.queryStats(CODE, null).getPv());
    }

    @Test
    void queryStatsTreatsMissingDataAsZero() {
        when(valueOperations.get(PV_KEY)).thenReturn(null);
        when(hyperLogLogOperations.size(UV_KEY)).thenReturn(0L);
        when(hyperLogLogOperations.size(IP_KEY)).thenReturn(0L);
        when(hashOperations.entries(REFERER_KEY)).thenReturn(Map.of());
        when(hashOperations.entries(DEVICE_KEY)).thenReturn(Map.of());

        var resp = statsService.queryStats(CODE, "");

        assertEquals(0L, resp.getPv());
        assertEquals(0L, resp.getUv());
        assertEquals(0, resp.getRefererStats().size());
    }
}
