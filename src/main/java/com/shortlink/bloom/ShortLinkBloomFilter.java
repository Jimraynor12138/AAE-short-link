package com.shortlink.bloom;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shortlink.config.ShortLinkProperties;
import com.shortlink.dao.LinkMapper;
import com.shortlink.entity.LinkDO;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 短码布隆过滤器（V3.1 引入，V3.4 修复每请求 7 次 GETBIT 的性能缺陷）。
 *
 * 解决的问题：用「空值缓存」防穿透会给每个不存在的短码写一个一次性 key；
 * 布隆过滤器在内存位图上判断「一定不存在」，可直接 404，连缓存都不用查。
 *
 * ## V3.4 的关键修复：本地位图
 * 旧实现每次 `mightContain` 都要对 k 个下标逐个 `GETBIT`（k=7），**每个跳转请求 7 个 Redis RTT**——
 * 这比它要省掉的那次缓存查询还贵，而且把 L1 本地缓存彻底架空（L1 命中仍要 7 次网络往返）。
 * 现在改为：启动时把位图（1MB 级）**一次性加载进本进程内存**，查询在本地数组上完成，**零 RTT**。
 *
 * ## 本地位图带来的新问题与对策
 * 多实例下本地快照可能过期（A 实例刚创建短码，B 实例本地还没有）→ 若直接信任本地"不存在"，
 * 就会把正常短链误判成 404（fail-close 事故，V3.1 已踩过一次）。
 * 因此**只对"本地判定不存在"的结论回查一次 Redis 确认**（pipelined，1 个 RTT）：
 * - Redis 也说不存在 → 安全拦截（扫描流量才会走这条路径，真实流量 99%+ 在上一步就返回了）
 * - Redis 说存在 → 本地快照过期 → 放行 + 触发本地位图重载（带 1 秒冷却，避免被扫描流量反复拉全量位图）
 * 可用 `shortlink.bloom.verify-on-local-miss=false` 关掉确认（单实例部署可省掉这次 RTT）。
 *
 * ## 其他不变的设计
 * - **fail-open**：过滤器不可用时一律放行，绝不把正常短链拦成 404
 * - 位图是外部状态，可能丢失：用 init 标记 + 位图 key **双重校验**，缺失即重新从 DB 预热
 * - 标准布隆过滤器不支持删除，被删短码会永久判为"可能存在"，实际跳转仍会正确 404
 */
@Slf4j
@Component
public class ShortLinkBloomFilter {

    /** 本地位图重载的最小间隔：防止"本地缺失 + 扫描流量"把整块位图反复拉回来 */
    private static final long RELOAD_MIN_INTERVAL_MILLIS = 1000L;

    private final BloomBitmapStore bitmapStore;
    private final ShortLinkProperties properties;
    private final LinkMapper linkMapper;

    /** 本进程内是否已确认过滤器可用 */
    private volatile boolean initialized = false;
    /** 防止多线程重复预热（正在预热时其他线程直接放行，不阻塞跳转） */
    private final AtomicBoolean initializing = new AtomicBoolean(false);
    /** 本地位图重载中的标记 */
    private final AtomicBoolean reloading = new AtomicBoolean(false);
    /** 上次重载时间（毫秒） */
    private final AtomicLong lastReloadAt = new AtomicLong(0L);

    /**
     * 本地位图快照：字节布局与 Redis 位图完全一致（MSB-first），
     * 因此可以直接整块 GET/SET 与 Redis 交换，不需要任何转换。
     * 读取不加锁：并发写入下最坏情况是"漏读一位"→ 走回查确认路径，安全。
     */
    private volatile byte[] localBits;

    /** 位数组长度与哈希次数：由容量与误判率推导，首次使用时计算并缓存 */
    private volatile long cachedBitSize;
    private volatile int cachedHashCount;

    public ShortLinkBloomFilter(BloomBitmapStore bitmapStore, ShortLinkProperties properties,
                                LinkMapper linkMapper) {
        this.bitmapStore = bitmapStore;
        this.properties = properties;
        this.linkMapper = linkMapper;
    }

    @PostConstruct
    public void warmupOnStartup() {
        if (properties.getBloom().isEnabled()) {
            initialize();
        }
    }

    /**
     * 是否可能存在
     *
     * @return false 表示「一定不存在」（可安全拦截为 404）；true 表示可能存在或过滤器不可用
     */
    public boolean mightContain(String code) {
        if (!properties.getBloom().isEnabled() || StrUtil.isBlank(code)) {
            return true;
        }
        if (!initialized && !initialize()) {
            return true;
        }
        byte[] bits = localBits;
        if (bits == null) {
            return true;
        }
        // 快路径：本地内存查询（零 Redis RTT）——真实流量基本都在这里返回 true
        long[] offsets = BloomFilterHash.offsets(code, bitSize(), hashCount());
        if (allBitsSet(bits, offsets)) {
            return true;
        }
        if (!properties.getBloom().isVerifyOnLocalMiss()) {
            return false;
        }
        return verifyInRedisAndReloadIfStale(code, offsets);
    }

    /**
     * 写入一个短码（创建短链时调用）
     */
    public void add(String code) {
        if (!properties.getBloom().isEnabled() || StrUtil.isBlank(code)) {
            return;
        }
        if (!initialized && !initialize()) {
            // 过滤器不可用：跳过写入，位图会在下次预热时从 DB 补齐
            return;
        }
        byte[] bits = localBits;
        if (bits == null) {
            return;
        }
        long[] offsets = BloomFilterHash.offsets(code, bitSize(), hashCount());
        setBitsInArray(bits, offsets);
        try {
            bitmapStore.setBits(properties.getBloom().getKey(), offsets);
        } catch (Exception e) {
            // 写失败不影响主流程：最坏是该短码不能被提前拦截，仍会走缓存/DB 得到正确结果
            log.error("布隆过滤器写入位图异常（已忽略，等待下次预热补齐）: code={}", code, e);
        }
    }

    /**
     * 确保过滤器可用；返回 false 表示「当前不可用，请放行」
     */
    public boolean initialize() {
        if (!properties.getBloom().isEnabled()) {
            return true;
        }
        if (initialized) {
            return true;
        }
        if (!initializing.compareAndSet(false, true)) {
            return false;
        }
        try {
            String bitmapKey = properties.getBloom().getKey();
            if (isReadyInRedis()) {
                byte[] loaded = bitmapStore.readBitmap(bitmapKey);
                if (loaded != null && loaded.length > 0) {
                    localBits = loaded;
                    initialized = true;
                    log.info("布隆过滤器本地位图加载完成: bytes={}, bitSize={}, hashCount={}",
                            loaded.length, bitSize(), hashCount());
                    return true;
                }
                log.warn("布隆过滤器位图读取为空，将重新从 DB 预热: key={}", bitmapKey);
            }
            int loaded = warmupFromDb();
            initialized = true;
            log.info("布隆过滤器预热完成: capacity={}, bitSize={}, hashCount={}, loaded={}, bytes={}",
                    properties.getBloom().getCapacity(), bitSize(), hashCount(), loaded, localBits.length);
            return true;
        } catch (Exception e) {
            log.error("布隆过滤器初始化失败，本次将放行所有请求（fail-open）", e);
            return false;
        } finally {
            initializing.set(false);
        }
    }

    /**
     * 位图在 Redis 中是否可用：**必须同时校验「预热标记」与「位图 key」都存在**。
     *
     * 为什么不能只看标记：Redis 可能因 maxmemory 淘汰策略丢掉大的位图 key 而保留小的标记 key。
     * 此时若信任标记，就会跳过预热，而位图读出来全是 0 —— 判定所有真实短码「一定不存在」，
     * 导致整站 404（fail-close 事故，V3.1 已踩过）。
     */
    private boolean isReadyInRedis() {
        if (!bitmapStore.exists(properties.getBloom().getInitFlagKey())) {
            return false;
        }
        if (!bitmapStore.exists(properties.getBloom().getKey())) {
            log.warn("检测到布隆过滤器位图丢失（标记仍在），将重新从 DB 预热: key={}",
                    properties.getBloom().getKey());
            return false;
        }
        return true;
    }

    /**
     * 从 DB 分批读取全部短码，在本地构建位图后**整块写入 Redis**（1 次 RTT，代替 N×k 次 SETBIT）
     */
    private int warmupFromDb() {
        String domain = properties.getDomain();
        int batchSize = properties.getBloom().getWarmupBatchSize();
        int byteSize = (int) ((bitSize() + 7) >>> 3);
        byte[] bits = new byte[byteSize];
        long lastId = 0L;
        int loaded = 0;
        while (true) {
            List<LinkDO> batch = linkMapper.selectList(new QueryWrapper<LinkDO>()
                    .select("id", "code")
                    .eq("domain", domain)
                    .gt("id", lastId)
                    .orderByAsc("id")
                    .last("LIMIT " + batchSize));
            if (batch.isEmpty()) {
                break;
            }
            for (LinkDO link : batch) {
                setBitsInArray(bits, BloomFilterHash.offsets(link.getCode(), bitSize(), hashCount()));
            }
            loaded += batch.size();
            lastId = batch.get(batch.size() - 1).getId();
            if (batch.size() < batchSize) {
                break;
            }
        }
        bitmapStore.writeBitmap(properties.getBloom().getKey(), bits);
        bitmapStore.markInit(properties.getBloom().getInitFlagKey());
        localBits = bits;
        return loaded;
    }

    /**
     * 「本地判定不存在」时回查 Redis 确认，顺便修正过期快照
     */
    private boolean verifyInRedisAndReloadIfStale(String code, long[] offsets) {
        try {
            boolean[] remoteBits = bitmapStore.readBits(properties.getBloom().getKey(), offsets);
            for (boolean bit : remoteBits) {
                if (!bit) {
                    // Redis 也确认不存在：可以安全拦截（这条路径只有扫描/不存在的短码才会走）
                    return false;
                }
            }
            // Redis 说存在而本地没有 → 本地快照过期（多为其他实例刚创建的短码）
            log.debug("布隆过滤器本地快照过期，本次放行并触发重载: code={}", code);
            reloadLocalBits();
            return true;
        } catch (Exception e) {
            log.error("布隆过滤器回查 Redis 异常，放行以避免误拦（fail-open）", e);
            return true;
        }
    }

    /**
     * 重载本地位图（带冷却与并发保护：最多每 {@link #RELOAD_MIN_INTERVAL_MILLIS} 毫秒一次）
     */
    private void reloadLocalBits() {
        long now = System.currentTimeMillis();
        if (now - lastReloadAt.get() < RELOAD_MIN_INTERVAL_MILLIS) {
            return;
        }
        if (!reloading.compareAndSet(false, true)) {
            return;
        }
        try {
            lastReloadAt.set(now);
            byte[] loaded = bitmapStore.readBitmap(properties.getBloom().getKey());
            if (loaded != null && loaded.length > 0) {
                localBits = loaded;
                log.info("布隆过滤器本地位图已重载: bytes={}", loaded.length);
            }
        } catch (Exception e) {
            log.error("布隆过滤器本地位图重载失败（沿用旧快照）", e);
        } finally {
            reloading.set(false);
        }
    }

    private boolean allBitsSet(byte[] bits, long[] offsets) {
        for (long offset : offsets) {
            int index = (int) (offset >>> 3);
            if (index >= bits.length || (bits[index] & (0x80 >>> (offset & 7))) == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 本地置位：位序与 Redis SETBIT 一致（offset 0 是第一个字节的最高位）
     */
    private void setBitsInArray(byte[] bits, long[] offsets) {
        for (long offset : offsets) {
            int index = (int) (offset >>> 3);
            if (index < bits.length) {
                bits[index] |= (byte) (0x80 >>> (offset & 7));
            }
        }
    }

    private long bitSize() {
        if (cachedBitSize == 0L) {
            cachedBitSize = BloomFilterHash.bitSize(properties.getBloom().getCapacity(),
                    properties.getBloom().getFalsePositiveRate());
        }
        return cachedBitSize;
    }

    private int hashCount() {
        if (cachedHashCount == 0) {
            cachedHashCount = BloomFilterHash.hashCount(bitSize(), properties.getBloom().getCapacity());
        }
        return cachedHashCount;
    }
}
