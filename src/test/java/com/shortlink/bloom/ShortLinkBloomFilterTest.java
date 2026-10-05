package com.shortlink.bloom;

import com.shortlink.config.ShortLinkProperties;
import com.shortlink.dao.LinkMapper;
import com.shortlink.entity.LinkDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 布隆过滤器测试（V3.4）：本地位图零 RTT、缺失回查、快照过期自愈、fail-open
 */
@ExtendWith(MockitoExtension.class)
class ShortLinkBloomFilterTest {

    @Mock
    private BloomBitmapStore bitmapStore;
    @Mock
    private LinkMapper linkMapper;

    private ShortLinkProperties properties;
    private ShortLinkBloomFilter bloomFilter;

    @BeforeEach
    void setUp() {
        properties = new ShortLinkProperties();
        properties.setDomain("localhost:8080");
        properties.getBloom().setCapacity(1000L);
        properties.getBloom().setFalsePositiveRate(0.01d);
        bloomFilter = new ShortLinkBloomFilter(bitmapStore, properties, linkMapper);
    }

    /**
     * 模拟 Redis 中「标记 + 位图」的存在状态。
     * 用 lenient：isReadyInRedis 对标记短路，标记不存在时不会去查位图 key
     */
    private void redisState(boolean initFlag, boolean bitmap) {
        lenient().when(bitmapStore.exists(properties.getBloom().getInitFlagKey())).thenReturn(initFlag);
        lenient().when(bitmapStore.exists(properties.getBloom().getKey())).thenReturn(bitmap);
    }

    private LinkDO link(String code) {
        LinkDO link = new LinkDO();
        link.setId(1L);
        link.setCode(code);
        return link;
    }

    private long[] offsetsOf(String code) {
        return BloomFilterHash.offsets(code, BloomFilterHash.bitSize(1000L, 0.01d),
                BloomFilterHash.hashCount(BloomFilterHash.bitSize(1000L, 0.01d), 1000L));
    }

    @Test
    void disabledFilterLetsEverythingThrough() {
        properties.getBloom().setEnabled(false);

        assertTrue(bloomFilter.mightContain("anything"));
        bloomFilter.add("anything");

        verify(bitmapStore, never()).readBits(anyString(), any());
    }

    @Test
    void warmupBuildsLocalBitmapFromDbAndWritesItInOneCall() {
        redisState(false, false);
        when(linkMapper.selectList(any())).thenReturn(List.of(link("abc123")));

        assertTrue(bloomFilter.initialize());

        // 整块写入（而不是每个短码 k 次 SETBIT）
        verify(bitmapStore).writeBitmap(eq(properties.getBloom().getKey()), any(byte[].class));
        verify(bitmapStore).markInit(properties.getBloom().getInitFlagKey());
        // 预热后本地即可判定"可能存在"
        assertTrue(bloomFilter.mightContain("abc123"));
    }

    @Test
    void loadsLocalBitmapFromRedisWhenAlreadyWarmedUp() {
        redisState(true, true);
        when(bitmapStore.readBitmap(properties.getBloom().getKey())).thenReturn(new byte[1024]);

        assertTrue(bloomFilter.initialize());

        // 位图已存在：不走 DB 预热
        verify(linkMapper, never()).selectList(any());
    }

    @Test
    void bitmapMissingTriggersRewarmupEvenIfFlagExists() {
        // 危险场景：Redis 淘汰了大的位图 key 但留下小标记 —— 若不重新预热，
        // 位图读出来全是 0，会把所有真实短码误判为「一定不存在」（整站 404）
        redisState(true, false);
        when(linkMapper.selectList(any())).thenReturn(List.of(link("abc123")));

        assertTrue(bloomFilter.initialize());

        verify(linkMapper).selectList(any());
        verify(bitmapStore).writeBitmap(eq(properties.getBloom().getKey()), any(byte[].class));
    }

    @Test
    void localHitNeedsNoRedisBitRead() {
        // V3.4 核心修复点：本地判定"可能存在"时，一个 Redis RTT 都不花
        redisState(false, false);
        when(linkMapper.selectList(any())).thenReturn(List.of(link("abc123")));
        bloomFilter.initialize();

        assertTrue(bloomFilter.mightContain("abc123"));

        verify(bitmapStore, never()).readBits(anyString(), any());
    }

    @Test
    void addSetsLocalBitsAndRedisBits() {
        redisState(false, false);
        when(linkMapper.selectList(any())).thenReturn(List.of());
        bloomFilter.initialize();

        bloomFilter.add("newCode");

        ArgumentCaptor<long[]> offsetsCaptor = ArgumentCaptor.forClass(long[].class);
        verify(bitmapStore).setBits(eq(properties.getBloom().getKey()), offsetsCaptor.capture());
        assertArrayEquals(offsetsOf("newCode"), offsetsCaptor.getValue());
        // 本地同步置位 → 后续查询无需回查 Redis
        assertTrue(bloomFilter.mightContain("newCode"));
        verify(bitmapStore, never()).readBits(anyString(), any());
    }

    @Test
    void localMissIsVerifiedInRedisAndRejectedWhenAbsent() {
        redisState(false, false);
        when(linkMapper.selectList(any())).thenReturn(List.of(link("abc123")));
        bloomFilter.initialize();
        when(bitmapStore.readBits(eq(properties.getBloom().getKey()), any(long[].class)))
                .thenReturn(new boolean[]{true, false, true, true, true, true, true});

        assertFalse(bloomFilter.mightContain("not-exist-code"));

        verify(bitmapStore).readBits(eq(properties.getBloom().getKey()), any(long[].class));
    }

    @Test
    void staleLocalSnapshotIsVerifiedAndTriggersReload() {
        redisState(false, false);
        when(linkMapper.selectList(any())).thenReturn(List.of(link("abc123")));
        bloomFilter.initialize();
        // 本地没有但 Redis 全为 1：其他实例刚创建了该短码 → 必须放行并重载快照
        when(bitmapStore.readBits(eq(properties.getBloom().getKey()), any(long[].class)))
                .thenReturn(new boolean[]{true, true, true, true, true, true, true});
        when(bitmapStore.readBitmap(properties.getBloom().getKey())).thenReturn(new byte[2048]);

        assertTrue(bloomFilter.mightContain("created-by-other-instance"));

        verify(bitmapStore).readBitmap(properties.getBloom().getKey());
    }

    @Test
    void verifyDisabledTrustsLocalSnapshotWithoutRedisCall() {
        properties.getBloom().setVerifyOnLocalMiss(false);
        redisState(false, false);
        when(linkMapper.selectList(any())).thenReturn(List.of(link("abc123")));
        bloomFilter.initialize();

        // 单实例部署可以关掉确认，省掉这次 RTT（代价是多实例下可能误判）
        assertFalse(bloomFilter.mightContain("not-exist-code"));
        verify(bitmapStore, never()).readBits(anyString(), any());
    }

    @Test
    void redisVerificationFailureIsFailOpen() {
        redisState(false, false);
        when(linkMapper.selectList(any())).thenReturn(List.of(link("abc123")));
        bloomFilter.initialize();
        when(bitmapStore.readBits(anyString(), any(long[].class)))
                .thenThrow(new RuntimeException("redis down"));

        // 关键：Redis 故障时必须放行，绝不能把正常短链全部拦成 404
        assertTrue(bloomFilter.mightContain("abc123-unknown"));
    }

    @Test
    void initializationFailureIsFailOpen() {
        when(bitmapStore.exists(anyString())).thenThrow(new RuntimeException("redis down"));

        assertTrue(bloomFilter.mightContain("abc123"));
    }

    @Test
    void bitmapWriteFailureOnAddIsIgnored() {
        redisState(false, false);
        when(linkMapper.selectList(any())).thenReturn(List.of());
        bloomFilter.initialize();
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(bitmapStore).setBits(anyString(), any(long[].class));

        // 写入失败不影响主流程：本地已有位，最坏是重启后要重新预热
        bloomFilter.add("newCode");
        assertTrue(bloomFilter.mightContain("newCode"));
    }
}
