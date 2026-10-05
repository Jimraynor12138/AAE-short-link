package com.shortlink.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortlink.cache.CacheKeyBuilder;
import com.shortlink.cache.CacheRebuildLock;
import com.shortlink.cache.LocalLinkCache;
import com.shortlink.config.ShortLinkProperties;
import com.shortlink.dao.LinkMapper;
import com.shortlink.dto.LinkCacheDTO;
import com.shortlink.entity.LinkDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缓存异步刷新测试（V3.2）：单飞控制、正常刷新、短链已删除、失败保留旧值
 */
@ExtendWith(MockitoExtension.class)
class CacheRefreshServiceImplTest {

    private static final String KEY = "short-link:link:localhost:8080:abc123";

    @Mock
    private LinkMapper linkMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private CacheRebuildLock rebuildLock;
    @Mock
    private LocalLinkCache localLinkCache;

    private ShortLinkProperties properties;
    private CacheRefreshServiceImpl refreshService;

    @BeforeEach
    void setUp() {
        properties = new ShortLinkProperties();
        properties.setDomain("localhost:8080");
        // 用同步执行器让刷新在当前线程完成，断言才可确定
        Executor directExecutor = Runnable::run;
        refreshService = new CacheRefreshServiceImpl(linkMapper, stringRedisTemplate, new ObjectMapper(),
                properties, rebuildLock, directExecutor, localLinkCache);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(rebuildLock.tryLock(anyString())).thenReturn(true);
    }

    private LinkDO link() {
        LinkDO link = new LinkDO();
        link.setOriginalUrl("https://example.com/target");
        link.setEnableStatus(0);
        link.setValidType(1);
        return link;
    }

    @Test
    void refreshWritesCacheWithLogicalExpireAt() throws Exception {
        when(linkMapper.selectOne(any())).thenReturn(link());

        refreshService.refreshAsync("abc123");

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(KEY), jsonCaptor.capture(), any(Duration.class));
        LinkCacheDTO cached = new ObjectMapper().readValue(jsonCaptor.getValue(), LinkCacheDTO.class);
        assertNotNull(cached.getLogicalExpireAt());
        assertTrue(cached.getLogicalExpireAt() > System.currentTimeMillis());
        verify(rebuildLock).unlock("abc123");
        // 刷新同时更新本实例 L1，避免本地继续返回旧值
        verify(localLinkCache).put(eq(KEY), any(LinkCacheDTO.class));
    }

    @Test
    void refreshSkipsWhenAnotherInstanceIsRefreshing() {
        when(rebuildLock.tryLock("abc123")).thenReturn(false);

        refreshService.refreshAsync("abc123");

        verify(linkMapper, never()).selectOne(any());
        verify(rebuildLock, never()).unlock(anyString());
    }

    @Test
    void refreshWritesNullMarkerWhenLinkDeleted() {
        when(linkMapper.selectOne(any())).thenReturn(null);

        refreshService.refreshAsync("abc123");

        verify(valueOperations).set(KEY, CacheKeyBuilder.NULL_MARKER,
                Duration.ofSeconds(properties.getCache().getNullTtlSeconds()));
        // 短链已删除：本实例 L1 必须一起清掉，否则本地旧值还会继续跳转
        verify(localLinkCache).invalidate(KEY);
    }

    @Test
    void refreshFailureKeepsStaleValueAndReleasesLock() {
        when(linkMapper.selectOne(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> refreshService.refreshAsync("abc123"));

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
        verify(rebuildLock).unlock("abc123");
    }

    @Test
    void rejectedTaskDoesNotThrowAndClearsInFlightMark() {
        // 队列满被拒绝：不能抛异常，且必须清掉本机去重标记，否则该短码再也无法刷新
        AtomicInteger submissions = new AtomicInteger();
        Executor rejectingExecutor = command -> {
            submissions.incrementAndGet();
            throw new RejectedExecutionException("queue full");
        };
        CacheRefreshServiceImpl service = new CacheRefreshServiceImpl(linkMapper, stringRedisTemplate,
                new ObjectMapper(), properties, rebuildLock, rejectingExecutor, localLinkCache);

        assertDoesNotThrow(() -> service.refreshAsync("abc123"));
        assertDoesNotThrow(() -> service.refreshAsync("abc123"));

        // 两次都尝试提交 → 说明去重标记在拒绝后已被清理
        assertEquals(2, submissions.get());
    }

    @Test
    void duplicateRefreshIsDeduplicatedInSameInstance() {
        // 本机去重：上一次刷新任务尚未执行完时，同一短码不会重复入队
        List<Runnable> captured = new ArrayList<>();
        Executor capturingExecutor = captured::add;
        CacheRefreshServiceImpl service = new CacheRefreshServiceImpl(linkMapper, stringRedisTemplate,
                new ObjectMapper(), properties, rebuildLock, capturingExecutor, localLinkCache);

        service.refreshAsync("busy-code");
        service.refreshAsync("busy-code");
        assertEquals(1, captured.size(), "同一短码不应重复入队");

        // 任务执行完成后标记释放，可再次入队
        captured.get(0).run();
        service.refreshAsync("busy-code");
        assertEquals(2, captured.size());
    }
}
