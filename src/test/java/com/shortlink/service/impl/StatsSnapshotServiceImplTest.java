package com.shortlink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortlink.cache.CacheKeyBuilder;
import com.shortlink.config.ShortLinkProperties;
import com.shortlink.dao.LinkMapper;
import com.shortlink.dao.LinkStatsMapper;
import com.shortlink.entity.LinkDO;
import com.shortlink.entity.LinkStatsDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统计快照落库测试：快照读取、批量解析 link_id、跨零点日期、失败重试、停机 flush
 */
@ExtendWith(MockitoExtension.class)
class StatsSnapshotServiceImplTest {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final LocalDate TODAY = LocalDate.now();
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);
    private static final String CODE = "abc123";

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private HyperLogLogOperations<String, String> hyperLogLogOperations;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private LinkMapper linkMapper;
    @Mock
    private LinkStatsMapper linkStatsMapper;

    private StatsSnapshotServiceImpl snapshotService;

    @BeforeEach
    void setUp() {
        ShortLinkProperties properties = new ShortLinkProperties();
        properties.setDomain("localhost:8080");
        snapshotService = new StatsSnapshotServiceImpl(stringRedisTemplate, linkMapper, linkStatsMapper,
                new ObjectMapper(), properties);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(stringRedisTemplate.opsForHyperLogLog()).thenReturn(hyperLogLogOperations);
        lenient().when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    private void stubSnapshot(LocalDate date) {
        String d = date.format(FMT);
        when(valueOperations.get(CacheKeyBuilder.buildPvKey(d, CODE))).thenReturn("11");
        when(hyperLogLogOperations.size(CacheKeyBuilder.buildUvKey(d, CODE))).thenReturn(10L);
        when(hyperLogLogOperations.size(CacheKeyBuilder.buildIpKey(d, CODE))).thenReturn(8L);
        when(hashOperations.entries(CacheKeyBuilder.buildRefererKey(d, CODE)))
                .thenReturn(Map.of("www.baidu.com", "6", "direct", "5"));
        when(hashOperations.entries(CacheKeyBuilder.buildDeviceKey(d, CODE)))
                .thenReturn(Map.of("mobile", "11"));
    }

    private void stubLink() {
        LinkDO link = new LinkDO();
        link.setId(7L);
        link.setCode(CODE);
        when(linkMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(link));
    }

    @Test
    void flushWritesSnapshotFromRedis() {
        stubSnapshot(TODAY);
        stubLink();

        snapshotService.markDirty(CODE, TODAY);
        snapshotService.flush();

        ArgumentCaptor<LinkStatsDO> captor = ArgumentCaptor.forClass(LinkStatsDO.class);
        verify(linkStatsMapper).upsertStats(captor.capture());
        LinkStatsDO stats = captor.getValue();
        assertEquals(7L, stats.getLinkId());
        assertEquals(CODE, stats.getCode());
        assertEquals(TODAY, stats.getDate());
        assertEquals(11L, stats.getPv());
        assertEquals(10L, stats.getUv());
        assertEquals(8L, stats.getIpCnt());
        assertEquals(5L, stats.getDefaultCnt());
        assertTrue(stats.getRefererStats().contains("www.baidu.com"));
        assertTrue(stats.getDeviceStats().contains("mobile"));
    }

    @Test
    void crossMidnightUsesDirtyKeyDate() {
        // 昨天的脏数据在 0 点后才被 flush：必须按「昨天的 key」落库，而不是当成今天没数据丢掉
        stubSnapshot(YESTERDAY);
        stubLink();

        snapshotService.markDirty(CODE, YESTERDAY);
        snapshotService.flush();

        ArgumentCaptor<LinkStatsDO> captor = ArgumentCaptor.forClass(LinkStatsDO.class);
        verify(linkStatsMapper).upsertStats(captor.capture());
        assertEquals(YESTERDAY, captor.getValue().getDate());
        assertEquals(11L, captor.getValue().getPv());
    }

    @Test
    void flushWithoutDirtyCodesDoesNothing() {
        snapshotService.flush();
        verify(linkStatsMapper, never()).upsertStats(any());
    }

    @Test
    void flushSkipsWhenPvKeyExpired() {
        // 注：link_id 采用整批解析（一次 IN 查询），即使本批全部无数据也会查一次，属可接受开销
        when(valueOperations.get(CacheKeyBuilder.buildPvKey(TODAY.format(FMT), CODE))).thenReturn(null);
        when(linkMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        snapshotService.markDirty(CODE, TODAY);
        snapshotService.flush();
        snapshotService.flush();

        verify(linkStatsMapper, never()).upsertStats(any());
    }

    @Test
    void flushSkipsWhenLinkDeleted() {
        when(valueOperations.get(CacheKeyBuilder.buildPvKey(TODAY.format(FMT), CODE))).thenReturn("11");
        when(linkMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        snapshotService.markDirty(CODE, TODAY);
        snapshotService.flush();

        verify(linkStatsMapper, never()).upsertStats(any());
    }

    @Test
    void failedFlushKeepsKeyDirtyForRetry() {
        stubSnapshot(TODAY);
        stubLink();
        when(linkStatsMapper.upsertStats(any())).thenThrow(new RuntimeException("db down"));

        snapshotService.markDirty(CODE, TODAY);
        snapshotService.flush();
        snapshotService.flush();

        // 第一次失败后脏标记保留，第二次会再次尝试
        verify(linkStatsMapper, times(2)).upsertStats(any());
    }

    @Test
    void shutdownFlushesRemainingDirtyCodes() {
        stubSnapshot(TODAY);
        stubLink();

        snapshotService.markDirty(CODE, TODAY);
        snapshotService.flushOnShutdown();

        verify(linkStatsMapper).upsertStats(any());
    }

    @Test
    void scheduledRescanSkipsWhenDisabled() {
        // 关闭开关后不应触碰 Redis（避免无意义的 SCAN）
        ShortLinkProperties props = new ShortLinkProperties();
        props.setDomain("localhost:8080");
        props.getStatsPersist().setRescanEnabled(false);
        StatsSnapshotServiceImpl service = new StatsSnapshotServiceImpl(stringRedisTemplate, linkMapper,
                linkStatsMapper, new ObjectMapper(), props);

        service.scheduledRescan();

        verify(stringRedisTemplate, never()).scan(any(org.springframework.data.redis.core.ScanOptions.class));
    }

    @Test
    void rescanMarksCodesFoundInRedis() {
        // 兜底扫描：从 Redis 的 pv key 反解短码并标记脏，随后 flush 能落库
        String pvKey = CacheKeyBuilder.buildPvKey(TODAY.format(FMT), CODE);
        org.springframework.data.redis.core.Cursor<String> cursor =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.Cursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(pvKey);
        when(stringRedisTemplate.scan(any(org.springframework.data.redis.core.ScanOptions.class))).thenReturn(cursor);

        stubSnapshot(TODAY);
        stubLink();

        int marked = snapshotService.rescan();
        snapshotService.flush();

        assertEquals(1, marked);
        verify(linkStatsMapper).upsertStats(any());
    }
}
