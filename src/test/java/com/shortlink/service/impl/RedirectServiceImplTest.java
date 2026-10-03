package com.shortlink.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortlink.cache.CacheKeyBuilder;
import com.shortlink.config.ShortLinkProperties;
import com.shortlink.dao.LinkMapper;
import com.shortlink.dto.LinkCacheDTO;
import com.shortlink.entity.LinkDO;
import com.shortlink.service.VisitRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跳转服务单元测试（V1.1）：缓存命中/空值缓存/停用过期/未命中回填/降级
 */
@ExtendWith(MockitoExtension.class)
class RedirectServiceImplTest {

    private static final String KEY = "short-link:link:localhost:8080:abc123";

    @Mock
    private LinkMapper linkMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private VisitRecorder visitRecorder;

    private RedirectServiceImpl redirectService;
    private ObjectMapper objectMapper;
    private ShortLinkProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ShortLinkProperties();
        properties.setDomain("localhost:8080");
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        redirectService = new RedirectServiceImpl(linkMapper, stringRedisTemplate, objectMapper, properties, visitRecorder);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    /** 统一的调用入口（V2 起签名含 IP/UA/Referer） */
    private String resolve(String code) {
        return redirectService.resolveRedirectUrl(code, "127.0.0.1", "JUnit-Agent", null);
    }

    private LinkCacheDTO cacheDTO(Integer enableStatus, Integer validType, LocalDateTime validDate) {
        LinkCacheDTO dto = new LinkCacheDTO();
        dto.setOriginalUrl("https://example.com/target");
        dto.setEnableStatus(enableStatus);
        dto.setValidType(validType);
        dto.setValidDate(validDate);
        return dto;
    }

    private LinkDO linkDO(Integer enableStatus, Integer validType, LocalDateTime validDate) {
        LinkDO link = new LinkDO();
        link.setId(1L);
        link.setCode("abc123");
        link.setDomain("localhost:8080");
        link.setOriginalUrl("https://example.com/target");
        link.setGid("default");
        link.setEnableStatus(enableStatus);
        link.setValidType(validType);
        link.setValidDate(validDate);
        link.setDelFlag(0);
        return link;
    }

    @Test
    void cacheHitReturnsUrlWithoutDb() throws Exception {
        when(valueOperations.get(KEY)).thenReturn(objectMapper.writeValueAsString(cacheDTO(0, 1, null)));
        assertEquals("https://example.com/target", resolve("abc123"));
        verify(linkMapper, never()).selectOne(any());
    }

    @Test
    void cacheHitNullMarkerReturnsNullWithoutDb() {
        when(valueOperations.get(KEY)).thenReturn(CacheKeyBuilder.NULL_MARKER);
        assertNull(resolve("abc123"));
        verify(linkMapper, never()).selectOne(any());
    }

    @Test
    void cacheHitDisabledLinkReturnsNull() throws Exception {
        when(valueOperations.get(KEY)).thenReturn(objectMapper.writeValueAsString(cacheDTO(1, 1, null)));
        assertNull(resolve("abc123"));
    }

    @Test
    void cacheHitExpiredLinkReturnsNull() throws Exception {
        when(valueOperations.get(KEY)).thenReturn(
                objectMapper.writeValueAsString(cacheDTO(0, 2, LocalDateTime.now().minusMinutes(1))));
        assertNull(resolve("abc123"));
    }

    @Test
    void cacheMissQueriesDbAndFillsCache() {
        when(valueOperations.get(KEY)).thenReturn(null);
        when(linkMapper.selectOne(any())).thenReturn(linkDO(0, 1, null));
        assertEquals("https://example.com/target", resolve("abc123"));
        verify(valueOperations).set(eq(KEY), anyString(), any(Duration.class));
    }

    @Test
    void cacheMissNotFoundWritesNullMarker() {
        when(valueOperations.get(KEY)).thenReturn(null);
        when(linkMapper.selectOne(any())).thenReturn(null);
        assertNull(resolve("abc123"));
        verify(valueOperations).set(eq(KEY), eq(CacheKeyBuilder.NULL_MARKER), eq(Duration.ofSeconds(60)));
    }

    @Test
    void cacheMissDisabledLinkStillCachesSnapshot() {
        when(valueOperations.get(KEY)).thenReturn(null);
        when(linkMapper.selectOne(any())).thenReturn(linkDO(1, 1, null));
        assertNull(resolve("abc123"));
        verify(valueOperations).set(eq(KEY), anyString(), any(Duration.class));
    }

    @Test
    void redisReadFailureDegradesToDb() {
        when(valueOperations.get(KEY)).thenThrow(new RuntimeException("redis down"));
        when(linkMapper.selectOne(any())).thenReturn(linkDO(0, 1, null));
        assertEquals("https://example.com/target", resolve("abc123"));
    }

    @Test
    void redisWriteFailureDoesNotBreakRedirect() {
        when(valueOperations.get(KEY)).thenReturn(null);
        when(linkMapper.selectOne(any())).thenReturn(linkDO(0, 1, null));
        // set(key, value, Duration) 是 void 方法，需用 doThrow 打桩
        doThrow(new RuntimeException("redis write down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        assertEquals("https://example.com/target", resolve("abc123"));
    }

    @Test
    void corruptCacheValueFallsBackToDb() {
        // 缓存值损坏（非法 JSON）应回源查询而非直接失败
        when(valueOperations.get(KEY)).thenReturn("{broken json");
        when(linkMapper.selectOne(any())).thenReturn(linkDO(0, 1, null));
        assertEquals("https://example.com/target", resolve("abc123"));
    }

    @Test
    void recordsVisitOnSuccessfulRedirect() throws Exception {
        when(valueOperations.get(KEY)).thenReturn(objectMapper.writeValueAsString(cacheDTO(0, 1, null)));
        assertEquals("https://example.com/target", resolve("abc123"));
        verify(visitRecorder).record("abc123", "127.0.0.1", "JUnit-Agent", null);
    }

    @Test
    void passesRefererToRecorder() throws Exception {
        when(valueOperations.get(KEY)).thenReturn(objectMapper.writeValueAsString(cacheDTO(0, 1, null)));
        redirectService.resolveRedirectUrl("abc123", "10.0.0.1", "UA", "https://www.baidu.com/s?wd=x");
        verify(visitRecorder).record("abc123", "10.0.0.1", "UA", "https://www.baidu.com/s?wd=x");
    }

    @Test
    void doesNotRecordVisitWhenNotRedirectable() throws Exception {
        when(valueOperations.get(KEY)).thenReturn(objectMapper.writeValueAsString(cacheDTO(1, 1, null)));
        assertNull(resolve("abc123"));
        verify(visitRecorder, never()).record(anyString(), anyString(), anyString(), any());
    }

    @Test
    void cacheDisabledSkipsRedisAndQueriesDb() {
        // 压测基线场景：cache.enabled=false 时完全跳过 Redis，等价 V0 行为
        properties.getCache().setEnabled(false);
        when(linkMapper.selectOne(any())).thenReturn(linkDO(0, 1, null));

        assertEquals("https://example.com/target", resolve("abc123"));

        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void statsDisabledSkipsVisitRecording() throws Exception {
        properties.setStatsEnabled(false);
        when(valueOperations.get(KEY)).thenReturn(objectMapper.writeValueAsString(cacheDTO(0, 1, null)));

        assertEquals("https://example.com/target", resolve("abc123"));

        verify(visitRecorder, never()).record(anyString(), anyString(), anyString(), any());
    }

    @Test
    void isExpiredBranches() {
        assertFalse(redirectService.isExpired(cacheDTO(0, 1, null)));
        assertTrue(redirectService.isExpired(cacheDTO(0, 2, LocalDateTime.now().minusSeconds(1))));
        assertFalse(redirectService.isExpired(cacheDTO(0, 2, LocalDateTime.now().plusSeconds(1))));
        assertFalse(redirectService.isExpired(cacheDTO(0, 2, null)));
    }

    @Test
    void isRedirectableBranches() {
        assertFalse(redirectService.isRedirectable(cacheDTO(1, 2, LocalDateTime.now().minusSeconds(1))));
        assertTrue(redirectService.isRedirectable(cacheDTO(0, 1, null)));
    }
}
