package com.shortlink.common.ratelimit;

import com.shortlink.config.ShortLinkProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内滑动窗口限流器（V3.3）。
 *
 * 为什么用滑动窗口而不是固定窗口：固定窗口在窗口边界会出现"双倍突发"
 * （例如 60s/120 次，在第 59s 打满 120 次、第 61s 再打满 120 次 → 2s 内 240 次）。
 * 滑动窗口按"最近 windowSeconds 内的请求数"判断，边界更平滑。
 *
 * 为什么是进程内而不是 Redis 版：单机部署下进程内已经够用，且**限流器不该依赖它要保护的下游**
 * ——Redis 版限流在 Redis 故障时会失效，反而在最需要限流的时候不起作用。
 * 代价是多实例下限流阈值是"每实例"而非全局（生产可用 Redis + 本地兜底两级限流，本项目留作演进）。
 *
 * 实现要点：`ConcurrentHashMap.compute` 对单个 key 的读改写是原子的（锁住桶），
 * 天然避免了对同一客户端的并发计数错乱，且不需要显式锁。
 */
@Slf4j
@Component
public class SlidingWindowRateLimiter {

    /** key（客户端标识）→ 窗口内的请求时间戳队列 */
    private final ConcurrentHashMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    private final ShortLinkProperties properties;
    private final Clock clock;

    /**
     * 必须标注 @Autowired：本类有两个构造器（另一个供测试注入时钟），
     * 不标注时 Spring 会去查找默认无参构造器导致启动失败（已被 E2E 抓出）。
     */
    @Autowired
    public SlidingWindowRateLimiter(ShortLinkProperties properties) {
        this(properties, Clock.systemDefaultZone());
    }

    /**
     * 可注入时钟的构造器，便于单元测试控制"时间流逝"
     */
    SlidingWindowRateLimiter(ShortLinkProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 尝试获取一次访问许可
     *
     * @param key 限流维度（本项目用客户端 IP）
     * @return true 放行；false 触发限流
     */
    public boolean tryAcquire(String key) {
        ShortLinkProperties.RateLimit config = properties.getRateLimit();
        if (!config.isEnabled()) {
            return true;
        }
        long now = clock.millis();
        long windowMillis = config.getWindowSeconds() * 1000;
        int limit = config.getWriteLimitPerWindow();

        boolean[] allowed = new boolean[1];
        windows.compute(key, (k, timestamps) -> {
            Deque<Long> deque = timestamps == null ? new ArrayDeque<>() : timestamps;
            // 淘汰窗口外的请求记录
            while (!deque.isEmpty() && now - deque.peekFirst() >= windowMillis) {
                deque.pollFirst();
            }
            if (deque.size() < limit) {
                deque.addLast(now);
                allowed[0] = true;
            }
            // 空队列直接移除，避免"一次性客户端"在 map 里留下垃圾
            return deque.isEmpty() ? null : deque;
        });
        return allowed[0];
    }

    /**
     * 当前跟踪的客户端数量（诊断用）
     */
    public int trackedKeys() {
        return windows.size();
    }
}
