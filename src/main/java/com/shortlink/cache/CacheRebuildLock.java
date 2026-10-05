package com.shortlink.cache;

import com.shortlink.config.ShortLinkProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 缓存重建锁（V3.2 防击穿）。
 *
 * 用 Redis SETNX 实现「同一短码同一时刻只有一个重建者」：互斥重建与异步刷新共用。
 *
 * 关键取舍：
 * - **fail-open**：Redis 异常时返回 true（视作拿到锁）。宁可多一个请求回源，也不能因为
 *   锁不可用把所有请求都卡在等待上 —— 可用性优先于「省一次 DB 查询」。
 * - 锁带 TTL：持有者若崩溃，锁会自动过期，不会永久阻塞后续重建。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheRebuildLock {

    /** 锁 TTL（秒）：需大于一次回源重建的耗时，且不能太大以免崩溃后长时间无法重建 */
    private static final long LOCK_TTL_SECONDS = 5L;

    private final StringRedisTemplate stringRedisTemplate;
    private final ShortLinkProperties properties;

    /**
     * 尝试获取重建锁
     *
     * @return true 表示由本次调用者负责回源/刷新；false 表示已有其他请求在重建
     */
    public boolean tryLock(String code) {
        String key = CacheKeyBuilder.buildRebuildLockKey(properties.getDomain(), code);
        try {
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, "1", Duration.ofSeconds(LOCK_TTL_SECONDS));
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.error("获取缓存重建锁异常，放行本次回源（fail-open）: code={}", code, e);
            return true;
        }
    }

    /**
     * 释放重建锁（失败不影响正确性：最多等锁 TTL 自动过期）
     */
    public void unlock(String code) {
        String key = CacheKeyBuilder.buildRebuildLockKey(properties.getDomain(), code);
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.error("释放缓存重建锁异常（将由 TTL 自动过期）: code={}", code, e);
        }
    }
}
