package com.shortlink.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.shortlink.config.ShortLinkProperties;
import com.shortlink.dto.LinkCacheDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 本地一级缓存（V3.3，Caffeine）。
 *
 * 解决什么问题：单个爆款短码被高频访问时，所有请求都打同一个 Redis key →
 * 该 key 所在的分片（甚至单核 CPU）被打满，Redis 集群水平扩展也救不了单 key。
 * 加一层进程内缓存后，热点请求连网络都不出。
 *
 * 关键设计：
 * - **只缓存正向结果**：不存在 / 已停用等负向结果不进 L1（那里有布隆过滤器与 L2 空值缓存兜着），
 *   避免本地内存被扫描流量灌满
 * - **TTL 远小于 L2**（默认 30s vs 1800s）：L1 是"每个实例各有一份"的数据，
 *   广播失效可能丢失，靠短 TTL 把不一致窗口封顶
 * - L1 命中同样要过 `validDate/enableStatus` 应用侧校验（缓存的是快照，不是结论）
 */
@Slf4j
@Component
public class LocalLinkCache {

    private final ShortLinkProperties properties;
    private final Cache<String, LinkCacheDTO> cache;

    public LocalLinkCache(ShortLinkProperties properties) {
        this.properties = properties;
        ShortLinkProperties.Local config = properties.getCache().getLocal();
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .maximumSize(config.getMaxSize())
                .expireAfterWrite(Duration.ofSeconds(config.getTtlSeconds()));
        if (config.isRecordStats()) {
            builder.recordStats();
        }
        // build() 的 K/V 由调用方显式指定（Caffeine 泛型链的常见坑）
        this.cache = builder.<String, LinkCacheDTO>build();
        log.info("本地缓存初始化: enabled={}, maxSize={}, ttlSeconds={}",
                config.isEnabled(), config.getMaxSize(), config.getTtlSeconds());
    }

    public boolean isEnabled() {
        return properties.getCache().getLocal().isEnabled();
    }

    /**
     * @return 缓存值；未命中或本地缓存关闭时返回 null
     */
    public LinkCacheDTO get(String key) {
        if (!isEnabled()) {
            return null;
        }
        return cache.getIfPresent(key);
    }

    public void put(String key, LinkCacheDTO dto) {
        if (!isEnabled() || dto == null) {
            return;
        }
        cache.put(key, dto);
    }

    public void invalidate(String key) {
        if (!isEnabled()) {
            return;
        }
        cache.invalidate(key);
        log.debug("本地缓存已失效: key={}", key);
    }

    /**
     * 清空本地缓存（配置变更、手工排障用）
     */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    public CacheStats stats() {
        return cache.stats();
    }

    /**
     * 诊断用：当前近似条目数
     */
    public long estimatedSize() {
        return cache.estimatedSize();
    }
}
