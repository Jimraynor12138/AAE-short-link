package com.shortlink.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortlink.cache.CacheKeyBuilder;
import com.shortlink.cache.CacheRebuildLock;
import com.shortlink.cache.DegradationGuard;
import com.shortlink.cache.LocalLinkCache;
import com.shortlink.common.exception.DegradedException;
import com.shortlink.config.ShortLinkProperties;
import com.shortlink.dao.LinkMapper;
import com.shortlink.bloom.ShortLinkBloomFilter;
import com.shortlink.dto.LinkCacheDTO;
import com.shortlink.entity.LinkDO;
import com.shortlink.service.CacheRefreshService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Mock
    private ShortLinkBloomFilter bloomFilter;

    @Mock
    private CacheRefreshService cacheRefreshService;

    @Mock
    private CacheRebuildLock rebuildLock;

    @Mock
    private LocalLinkCache localLinkCache;

    @Mock
    private DegradationGuard degradationGuard;

    private RedirectServiceImpl redirectService;
    private ObjectMapper objectMapper;
    private ShortLinkProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ShortLinkProperties();
        properties.setDomain("localhost:8080");
        // 缩短互斥等待，避免测试变慢
        properties.getCache().getAntiBreakdown().setMutexWaitMillis(60);
        properties.getCache().getAntiBreakdown().setMutexWaitStepMillis(10);
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        redirectService = new RedirectServiceImpl(linkMapper, stringRedisTemplate, objectMapper, properties,
                visitRecorder, bloomFilter, cacheRefreshService, rebuildLock, localLinkCache, degradationGuard);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // 默认放行（等价 V2 行为）；需要验证拦截的用例单独打桩
        lenient().when(bloomFilter.mightContain(anyString())).thenReturn(true);
        // 默认抢到重建锁（leader 路径）；"跟车"路径的用例单独打桩为 false
        lenient().when(rebuildLock.tryLock(anyString())).thenReturn(true);
        // 默认 L1 未命中（走 L2）；验证 L1 命中的用例单独打桩
        lenient().when(localLinkCache.get(anyString())).thenReturn(null);
        // 默认正常态（空许可放行）；验证降级拒绝的用例单独打桩
        lenient().when(degradationGuard.tryAcquirePermit()).thenReturn(DegradationGuard.Permit.NOOP);
        lenient().when(degradationGuard.isDegraded()).thenReturn(false);
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
    void bloomRejectionReturnsNullWithoutTouchingCacheOrDb() {
        // V3.1 防穿透：布隆过滤器判定「一定不存在」时直接返回，缓存与 DB 都不查
        when(bloomFilter.mightContain("nope")).thenReturn(false);

        assertNull(resolve("nope"));

        verify(valueOperations, never()).get(anyString());
        verify(linkMapper, never()).selectOne(any());
        verify(visitRecorder, never()).record(anyString(), anyString(), anyString(), any());
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

    // ==================== V3.2 防击穿 ====================

    @Test
    void logicallyExpiredCacheReturnsStaleValueAndTriggersRefresh() throws Exception {
        // 逻辑过期：请求线程立即拿到旧值（不阻塞），同时触发后台异步重建
        LinkCacheDTO dto = cacheDTO(0, 1, null);
        dto.setLogicalExpireAt(System.currentTimeMillis() - 1000);
        when(valueOperations.get(KEY)).thenReturn(objectMapper.writeValueAsString(dto));

        assertEquals("https://example.com/target", resolve("abc123"));
        verify(cacheRefreshService).refreshAsync("abc123");
    }

    @Test
    void freshCacheDoesNotTriggerRefresh() throws Exception {
        LinkCacheDTO dto = cacheDTO(0, 1, null);
        dto.setLogicalExpireAt(System.currentTimeMillis() + 60_000);
        when(valueOperations.get(KEY)).thenReturn(objectMapper.writeValueAsString(dto));

        assertEquals("https://example.com/target", resolve("abc123"));
        verify(cacheRefreshService, never()).refreshAsync(anyString());
    }

    @Test
    void legacyCacheValueWithoutLogicalExpireAtIsTreatedAsFresh() throws Exception {
        // 兼容升级前的存量缓存：没有 logicalExpireAt 字段时不能判定为过期，
        // 否则升级瞬间会触发全量刷新（自己制造一次小雪崩）
        when(valueOperations.get(KEY))
                .thenReturn("{\"originalUrl\":\"https://example.com/target\",\"enableStatus\":0,\"validType\":1}");

        assertEquals("https://example.com/target", resolve("abc123"));
        verify(cacheRefreshService, never()).refreshAsync(anyString());
    }

    @Test
    void refreshTriggerFailureDoesNotBreakRedirect() throws Exception {
        LinkCacheDTO dto = cacheDTO(0, 1, null);
        dto.setLogicalExpireAt(System.currentTimeMillis() - 1000);
        when(valueOperations.get(KEY)).thenReturn(objectMapper.writeValueAsString(dto));
        doThrow(new RuntimeException("executor rejected")).when(cacheRefreshService).refreshAsync(anyString());

        assertEquals("https://example.com/target", resolve("abc123"));
    }

    @Test
    void mutexRebuildWaitsAndReusesFilledCache() throws Exception {
        // 没抢到锁 → 等待 leader 回填 → 直接复用，本轮不再回源 DB
        when(rebuildLock.tryLock("abc123")).thenReturn(false);
        LinkCacheDTO dto = cacheDTO(0, 1, null);
        when(valueOperations.get(KEY)).thenReturn(null, objectMapper.writeValueAsString(dto));

        assertEquals("https://example.com/target", resolve("abc123"));

        verify(linkMapper, never()).selectOne(any());
        // 跟车者不能释放 leader 的锁
        verify(rebuildLock, never()).unlock(anyString());
    }

    @Test
    void mutexRebuildLeaderFillsCacheAndUnlocks() {
        when(valueOperations.get(KEY)).thenReturn(null);
        when(linkMapper.selectOne(any())).thenReturn(linkDO(0, 1, null));

        assertEquals("https://example.com/target", resolve("abc123"));

        verify(valueOperations).set(eq(KEY), anyString(), any(Duration.class));
        verify(rebuildLock).unlock("abc123");
    }

    @Test
    void mutexRebuildFallsBackToDbWithoutFillingWhenWaitTimesOut() {
        // leader 迟迟未回填（慢/异常）：跟车者自行回源兜底，但不再写缓存，避免写放大
        when(rebuildLock.tryLock("abc123")).thenReturn(false);
        when(valueOperations.get(KEY)).thenReturn(null);
        when(linkMapper.selectOne(any())).thenReturn(linkDO(0, 1, null));

        assertEquals("https://example.com/target", resolve("abc123"));

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
        verify(rebuildLock, never()).unlock(anyString());
    }

    @Test
    void mutexRebuildLeaderReleasesLockOnFailure() {
        // leader 回源抛异常时也必须解锁，否则同 key 在锁 TTL 内无法重建
        when(valueOperations.get(KEY)).thenReturn(null);
        when(linkMapper.selectOne(any())).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> resolve("abc123"));

        verify(rebuildLock).unlock("abc123");
    }

    // ==================== V3.3 多级缓存 + 降级保护 ====================

    @Test
    void localCacheHitSkipsRedisAndDb() {
        // L1 命中：热点短码不出进程，既不查 L2 也不查 DB
        LinkCacheDTO local = cacheDTO(0, 1, null);
        local.setLogicalExpireAt(System.currentTimeMillis() + 60_000);
        when(localLinkCache.get(KEY)).thenReturn(local);

        assertEquals("https://example.com/target", resolve("abc123"));

        verify(stringRedisTemplate, never()).opsForValue();
        verify(linkMapper, never()).selectOne(any());
    }

    @Test
    void logicallyExpiredLocalHitReturnsStaleValueAndTriggersRefresh() {
        LinkCacheDTO local = cacheDTO(0, 1, null);
        local.setLogicalExpireAt(System.currentTimeMillis() - 1_000);
        when(localLinkCache.get(KEY)).thenReturn(local);

        assertEquals("https://example.com/target", resolve("abc123"));
        verify(cacheRefreshService).refreshAsync("abc123");
    }

    @Test
    void l2HitFillsLocalCache() throws Exception {
        when(valueOperations.get(KEY)).thenReturn(objectMapper.writeValueAsString(cacheDTO(0, 1, null)));

        assertEquals("https://example.com/target", resolve("abc123"));

        verify(localLinkCache).put(eq(KEY), any(LinkCacheDTO.class));
    }

    @Test
    void redisFailureMarksDegradedAndFallsBackToDb() {
        when(valueOperations.get(KEY)).thenThrow(new RuntimeException("redis down"));
        when(linkMapper.selectOne(any())).thenReturn(linkDO(0, 1, null));

        assertEquals("https://example.com/target", resolve("abc123"));

        verify(degradationGuard).markDegraded(anyString());
        verify(linkMapper).selectOne(any());
    }

    @Test
    void healthyRedisClearsDegradedState() throws Exception {
        when(valueOperations.get(KEY)).thenReturn(objectMapper.writeValueAsString(cacheDTO(0, 1, null)));

        resolve("abc123");

        verify(degradationGuard).markHealthy();
    }

    @Test
    void degradedWithoutDbPermitFailsFastWithDegradedException() {
        // 降级态且回源并发已满：快速失败（503），绝不能继续压 DB
        when(valueOperations.get(KEY)).thenReturn(null);
        when(degradationGuard.tryAcquirePermit()).thenReturn(null);

        assertThrows(DegradedException.class, () -> resolve("abc123"));

        verify(linkMapper, never()).selectOne(any());
        // 抢到的重建锁必须释放，否则同 key 在锁 TTL 内无法重建
        verify(rebuildLock).unlock("abc123");
    }

    @Test
    void degradedWithDbPermitStillServesRequest() {
        // 降级态但还有许可：正常回源并归还许可
        when(valueOperations.get(KEY)).thenReturn(null);
        when(degradationGuard.tryAcquirePermit()).thenReturn(() -> {
        });
        when(linkMapper.selectOne(any())).thenReturn(linkDO(0, 1, null));

        assertEquals("https://example.com/target", resolve("abc123"));

        verify(linkMapper).selectOne(any());
    }
}
