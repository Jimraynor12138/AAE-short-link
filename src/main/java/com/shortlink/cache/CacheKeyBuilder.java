package com.shortlink.cache;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 缓存 Key 统一构建器 + TTL 抖动计算。
 *
 * <p>Key 设计：short-link:link:{domain}:{code}
 * <ul>
 *   <li>短前缀节省内存（本系统 key 数量 = 短链数量，量大时前缀长度直接影响内存占用）</li>
 *   <li>domain 进 key：V0 虽是单域名，但这是为多域名预留的正确形态，避免未来缓存迁移</li>
 * </ul>
 */
public final class CacheKeyBuilder {

    /** 空值缓存标记值：防穿透，表示该短码确认不存在 */
    public static final String NULL_MARKER = "null";

    private static final String LINK_KEY_PREFIX = "short-link:link:";
    private static final String STATS_KEY_PREFIX = "short-link:stats:";
    private static final String REBUILD_LOCK_KEY_PREFIX = "short-link:lock:rebuild:";

    private CacheKeyBuilder() {
    }

    /**
     * 短链跳转缓存 key
     */
    public static String buildLinkKey(String domain, String code) {
        return LINK_KEY_PREFIX + domain + ":" + code;
    }

    /**
     * 缓存重建锁 key（V3.2 防击穿）：互斥重建与异步刷新共用，保证同一短码同一时刻只有一个重建者
     */
    public static String buildRebuildLockKey(String domain, String code) {
        return REBUILD_LOCK_KEY_PREFIX + domain + ":" + code;
    }

    /**
     * 日 PV 计数 key（String INCR）
     */
    public static String buildPvKey(String date, String code) {
        return STATS_KEY_PREFIX + "pv:" + date + ":" + code;
    }

    /**
     * 日 UV 去重 key（HyperLogLog，约 12KB/key，可容纳海量基数，远优于 Set）
     */
    public static String buildUvKey(String date, String code) {
        return STATS_KEY_PREFIX + "uv:" + date + ":" + code;
    }

    /**
     * 日独立 IP 去重 key（HyperLogLog）
     */
    public static String buildIpKey(String date, String code) {
        return STATS_KEY_PREFIX + "ip:" + date + ":" + code;
    }

    /**
     * 日来源分布 key（Hash：来源域名 -> 次数），V2 新增
     */
    public static String buildRefererKey(String date, String code) {
        return STATS_KEY_PREFIX + "referer:" + date + ":" + code;
    }

    /**
     * 日设备分布 key（Hash：设备类型 -> 次数），V2 新增
     */
    public static String buildDeviceKey(String date, String code) {
        return STATS_KEY_PREFIX + "device:" + date + ":" + code;
    }

    /**
     * 计算带随机抖动的实际 TTL（秒）：
     * base + [0, jitter]，防止大量 key 在同一时刻集体过期引发缓存雪崩
     */
    public static long jitteredTtlSeconds(long baseSeconds, long jitterSeconds) {
        if (jitterSeconds <= 0) {
            return baseSeconds;
        }
        return baseSeconds + ThreadLocalRandom.current().nextLong(jitterSeconds + 1);
    }
}
