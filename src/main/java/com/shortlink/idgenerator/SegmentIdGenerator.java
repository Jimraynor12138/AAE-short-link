package com.shortlink.idgenerator;

import com.shortlink.config.ShortLinkProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * V3.5 发号器：号段模式（双 buffer）。
 *
 * ## 为什么需要它
 * V1.2 的 Redis INCR 是「一号一次 RTT」。号段模式把它变成「一段一次」：
 * 一次从 `t_segment` 申请 step 个号（默认 1000）放在内存里发，**发号的网络开销降为 1/step**。
 * 同时因为手上有库存，号源（DB）短暂抖动时还能继续发号 —— 可用性也更好。
 *
 * ## 双 buffer（Leaf-segment 的核心优化）
 * 如果"用完一段才去申请"，每次段切换都要等一次 DB RTT（尖刺）。
 * 双 buffer 的做法：**当前段剩余不足 {@code prefetch-remaining-percent}% 时，异步预取下一段**，
 * 段用尽时直接切到预留的下一段 —— 请求线程零等待。
 *
 * ## 并发设计
 * - 发号热路径：`AtomicLong` CAS 自增（无锁）
 * - 段切换：`ReentrantLock` + double-check（只有极少量线程能进入，且切换本身很快）
 * - 本类只做「内存发号 + 切换」，DB 交互交给 {@link SegmentAllocator}（带事务的独立 Bean）
 *
 * ## 已知代价
 * - 号有「段间跳变」（不严格连续，但全局唯一、趋势递增）
 * - 进程重启会浪费当前段未用完的号（号只要求唯一，不要求连续，功能上无害）
 * - 多实例天然共享 `t_segment` 一行，靠行锁串行分配 → 天然不重号
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "shortlink.id-generator-type", havingValue = "segment")
public class SegmentIdGenerator implements IdGenerator {

    private final SegmentAllocator allocator;
    private final ShortLinkProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final Executor prefetchExecutor;

    /** 段切换锁：只在段用尽/切换时竞争，热路径不经过它 */
    private final ReentrantLock switchLock = new ReentrantLock();
    /** 预取中标记：避免同一时刻发起多个预取 */
    private final AtomicBoolean prefetching = new AtomicBoolean(false);

    /** 当前段已发放到的号（0 表示还没发过） */
    private final AtomicLong cursor = new AtomicLong(0L);
    /** 当前段上界：cursor 达到它就说明本段用完 */
    private volatile long maxInUse = 0L;
    /** 预取到的下一段上界（0 或 ≤ maxInUse 表示没有可用预取） */
    private volatile long prefetchedMax = 0L;

    public SegmentIdGenerator(SegmentAllocator allocator,
                              ShortLinkProperties properties,
                              JdbcTemplate jdbcTemplate,
                              @Qualifier("segmentPrefetchExecutor") Executor prefetchExecutor) {
        this.allocator = allocator;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.prefetchExecutor = prefetchExecutor;
    }

    /**
     * 启动时把号段起点对齐到「数据库已用过的最大号」之上。
     *
     * 必要性：切换发号器（例如从 redis 换回 segment）时，`t_segment.max_id` 可能落后于
     * t_link 的真实进度，若不复位就会连续撞已用过的号（唯一索引拦截 → 创建失败）
     */
    @PostConstruct
    public void seed() {
        ShortLinkProperties.Segment config = properties.getSegment();
        long dbMaxId = queryDbMaxId();
        if (dbMaxId > 0) {
            allocator.raiseTo(config.getBizTag(), config.getStep(), dbMaxId);
        }
        // 本地起点也必须对齐：cursor/maxInUse 都设成 dbMaxId，第一段才会从 dbMaxId+1 开始，
        // 否则首段会覆盖 DB 里已经用过的号（唯一索引拦截 → 创建连续失败）
        cursor.set(dbMaxId);
        maxInUse = dbMaxId;
        log.info("号段发号器就绪: bizTag={}, step={}, dbMaxId={}", config.getBizTag(), config.getStep(), dbMaxId);
    }

    @Override
    public long nextId() {
        while (true) {
            long current = cursor.get();
            if (current < maxInUse) {
                if (cursor.compareAndSet(current, current + 1)) {
                    maybePrefetch();
                    return current + 1;
                }
                continue;   // CAS 竞争失败：重读
            }
            // 本段用完 → 切换（优先用预取段；没有则同步分配，这是唯一会等待的路径）
            rotateSegment();
        }
    }

    @Override
    public void recoverAfterConflict() {
        // 短码冲突说明号段落后于 DB：把 t_segment 抬升到 DB 最大值之上，
        // 并让本地缓冲直接跳到安全区间（否则会在已用过的号上继续撞）
        long dbMaxId = queryDbMaxId();
        if (dbMaxId <= 0) {
            return;
        }
        ShortLinkProperties.Segment config = properties.getSegment();
        allocator.raiseTo(config.getBizTag(), config.getStep(), dbMaxId);
        switchLock.lock();
        try {
            if (maxInUse < dbMaxId) {
                cursor.set(Math.max(cursor.get(), dbMaxId));
                maxInUse = dbMaxId;
                prefetchedMax = 0L;
            }
        } finally {
            switchLock.unlock();
        }
        log.warn("号段发号器自愈：已抬升至 dbMaxId={}", dbMaxId);
    }

    /**
     * 段切换：拿走预取段，或同步分配一段
     */
    private void rotateSegment() {
        switchLock.lock();
        try {
            if (cursor.get() < maxInUse) {
                return;     // 其他线程已经换好段（double-check）
            }
            long candidate = prefetchedMax;
            if (candidate > maxInUse) {
                maxInUse = candidate;
                prefetchedMax = 0L;
                prefetching.set(false);
                log.debug("切换到预取号段: maxId={}", candidate);
                return;
            }
            // 没有可用预取（预取失败/尚未触发/刚被切换消耗）→ 同步分配兜底
            long newMax = allocateWithRetry();
            if (newMax <= maxInUse) {
                throw new IllegalStateException("号段分配异常：新段上界 " + newMax + " 不大于当前段 " + maxInUse);
            }
            maxInUse = newMax;
            log.info("同步分配号段: maxId={}", newMax);
        } finally {
            switchLock.unlock();
        }
    }

    /**
     * 双 buffer 触发点：当前段剩余不足阈值时异步预取下一段
     */
    private void maybePrefetch() {
        if (prefetching.get() || prefetchedMax > maxInUse) {
            return;
        }
        long remaining = maxInUse - cursor.get();
        long threshold = Math.max(1L, (long) properties.getSegment().getStep()
                * properties.getSegment().getPrefetchRemainingPercent() / 100);
        if (remaining > threshold) {
            return;
        }
        if (!prefetching.compareAndSet(false, true)) {
            return;
        }
        try {
            prefetchExecutor.execute(this::doPrefetch);
        } catch (RejectedExecutionException e) {
            // AbortPolicy 明确抛出：重置标记，等下一次取号再试（段耗尽时还有同步分配兜底）
            prefetching.set(false);
            log.warn("号段预取任务被拒绝（队列已满），将在段耗尽时同步分配");
        }
    }

    private void doPrefetch() {
        try {
            long newMax = allocateWithRetry();
            if (newMax > maxInUse) {
                prefetchedMax = newMax;
                log.debug("异步预取号段完成: maxId={}", newMax);
            }
            // 若已过期（同步分配把 maxInUse 抬得更高），这段号就会被浪费 —— 号段模式的正常代价
        } catch (Exception e) {
            log.error("异步预取号段失败，将在当前段耗尽时同步分配兜底", e);
        } finally {
            prefetching.set(false);
        }
    }

    /**
     * 带重试的号段分配：DB 抖动时不立刻让创建失败
     */
    private long allocateWithRetry() {
        ShortLinkProperties.Segment config = properties.getSegment();
        int maxAttempts = Math.max(1, config.getAllocRetryTimes());
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return allocator.allocate(config.getBizTag(), config.getStep());
            } catch (RuntimeException e) {
                last = e;
                log.warn("号段分配失败，第 {}/{} 次: bizTag={}", attempt, maxAttempts, config.getBizTag(), e);
                if (attempt < maxAttempts) {
                    sleepQuietly(config.getAllocRetryIntervalMillis());
                }
            }
        }
        throw new IllegalStateException("号段分配失败（已重试 " + maxAttempts + " 次）", last);
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * DB 当前最大短链 id：号段起点的对齐基准（用 t_link 而不是 t_sequence，
     * 因为 t_link.id 才是真正被使用过的号）
     */
    private long queryDbMaxId() {
        Long maxId = jdbcTemplate.queryForObject("SELECT IFNULL(MAX(id), 0) FROM t_link", Long.class);
        return maxId == null ? 0L : maxId;
    }
}
