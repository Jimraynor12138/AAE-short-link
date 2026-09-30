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

    private CacheKeyBuilder() {
    }

    /**
     * 短链跳转缓存 key
     */
    public static String buildLinkKey(String domain, String code) {
        return LINK_KEY_PREFIX + domain + ":" + code;
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
