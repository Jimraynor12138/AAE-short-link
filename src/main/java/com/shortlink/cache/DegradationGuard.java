package com.shortlink.cache;

import com.shortlink.config.ShortLinkProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 降级闸门（V3.3 防雪崩）。
 *
 * 缓存雪崩的最终形态是 **Redis 整体故障**：所有跳转流量同时失去缓存，直接砸向 MySQL。
 * 此时什么都不做的话，DB 连接池会被瞬间占满，所有请求一起超时 —— 系统从"慢"变成"死"。
 *
 * 本组件把"Redis 故障"变成**可感知的状态**（降级态），并在降级期间对回源 DB 的并发加闸门：
 * 最多 maxConcurrentDbQueries 个请求同时查库，其余请求等待 acquireTimeoutMillis 后快速失败（503）。
 * 取舍明确：**宁可让少量请求失败，也不能让数据库挂掉**——DB 是全站共享的最后防线。
 *
 * 状态是"粘滞"的：一次 Redis 异常后，在 degradeWindowSeconds 内都按降级处理，
 * 避免抖动式反复切换（正常/降级来回跳）造成流量脉冲。
 *
 * 许可用 {@link Permit}（AutoCloseable）表达：正常态返回空许可、降级态返回真实许可，
 * 调用方统一在 try-with-resources 中释放 —— 从 API 层面杜绝"降级窗口刚好在请求期间结束导致许可泄漏"。
 */
@Slf4j
@Component
public class DegradationGuard {

    /**
     * 回源许可：非 null 即代表"允许回源"，close() 负责归还（空许可 close 无副作用）
     */
    public interface Permit extends AutoCloseable {

        Permit NOOP = () -> {
        };

        @Override
        void close();
    }

    private final ShortLinkProperties properties;
    private final Semaphore dbPermits;

    /** 降级态截止时间戳（毫秒），0 表示正常 */
    private final AtomicLong degradedUntil = new AtomicLong(0);

    /** 被拒绝的回源请求数：突增说明缓存层故障 + 流量压力大（可观测指标） */
    private final AtomicLong rejectedCount = new AtomicLong();

    public DegradationGuard(ShortLinkProperties properties) {
        this.properties = properties;
        this.dbPermits = new Semaphore(properties.getCache().getDegradation().getMaxConcurrentDbQueries());
    }

    /**
     * 标记缓存层故障，进入降级态
     */
    public void markDegraded(String reason) {
        if (!properties.getCache().getDegradation().isEnabled()) {
            return;
        }
        long windowMillis = properties.getCache().getDegradation().getDegradeWindowSeconds() * 1000;
        long newUntil = System.currentTimeMillis() + windowMillis;
        // 仅在"正常 → 降级"的跳变时打日志，避免异常风暴下日志刷屏
        if (degradedUntil.getAndSet(newUntil) == 0) {
            log.error("缓存层异常，进入降级态（{}s 内限制回源并发以保护数据库）: reason={}",
                    properties.getCache().getDegradation().getDegradeWindowSeconds(), reason);
        }
    }

    /**
     * 标记缓存层恢复，退出降级态
     */
    public void markHealthy() {
        if (degradedUntil.getAndSet(0) != 0) {
            log.info("缓存层已恢复，退出降级态");
        }
    }

    public boolean isDegraded() {
        return degradedUntil.get() > System.currentTimeMillis();
    }

    /**
     * 申请回源许可
     *
     * @return 正常态返回空许可；降级态下拿到信号量则返回真实许可；被拒绝返回 null
     */
    public Permit tryAcquirePermit() {
        if (!properties.getCache().getDegradation().isEnabled() || !isDegraded()) {
            return Permit.NOOP;
        }
        try {
            boolean acquired = dbPermits.tryAcquire(
                    properties.getCache().getDegradation().getAcquireTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (acquired) {
                return dbPermits::release;
            }
            long rejected = rejectedCount.incrementAndGet();
            log.warn("降级态下回源并发已满，拒绝本次请求（累计拒绝 {}）", rejected);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 当前可用回源许可数（诊断用）
     */
    public int availablePermits() {
        return dbPermits.availablePermits();
    }

    public long rejectedCount() {
        return rejectedCount.get();
    }
}
