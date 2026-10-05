package com.shortlink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortlink.cache.CacheKeyBuilder;
import com.shortlink.cache.CacheRebuildLock;
import com.shortlink.cache.LinkCacheAssembler;
import com.shortlink.cache.LocalLinkCache;
import com.shortlink.config.ShortLinkProperties;
import com.shortlink.dao.LinkMapper;
import com.shortlink.dto.LinkCacheDTO;
import com.shortlink.entity.LinkDO;
import com.shortlink.service.CacheRefreshService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 缓存异步刷新实现（V3.2）。
 *
 * 三道闸门，逐层削减「热点失效瞬间的并发回源」：
 * 1. **本机去重**：同一短码在本实例内已有刷新任务在跑，就不再提交（防止队列被打爆）
 * 2. **分布式重建锁**：多实例之间也只允许一个真正回源
 * 3. **有界队列 + 丢弃**：极端情况下直接放弃刷新，请求侧不受影响
 */
@Slf4j
@Service
public class CacheRefreshServiceImpl implements CacheRefreshService {

    /** 本实例正在刷新的短码（防止同一 key 重复入队） */
    private final Set<String> refreshingCodes = ConcurrentHashMap.newKeySet();

    private final LinkMapper linkMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ShortLinkProperties properties;
    private final CacheRebuildLock rebuildLock;
    private final Executor cacheRefreshExecutor;
    private final LocalLinkCache localLinkCache;

    public CacheRefreshServiceImpl(LinkMapper linkMapper,
                                   StringRedisTemplate stringRedisTemplate,
                                   ObjectMapper objectMapper,
                                   ShortLinkProperties properties,
                                   CacheRebuildLock rebuildLock,
                                   @Qualifier("cacheRefreshExecutor") Executor cacheRefreshExecutor,
                                   LocalLinkCache localLinkCache) {
        this.linkMapper = linkMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.rebuildLock = rebuildLock;
        this.cacheRefreshExecutor = cacheRefreshExecutor;
        this.localLinkCache = localLinkCache;
    }

    @Override
    public void refreshAsync(String code) {
        if (!refreshingCodes.add(code)) {
            // 本实例已在刷新该短码：直接跳过，避免同一 key 重复入队
            return;
        }
        try {
            cacheRefreshExecutor.execute(() -> {
                try {
                    doRefresh(code);
                } finally {
                    refreshingCodes.remove(code);
                }
            });
        } catch (RejectedExecutionException e) {
            // 依赖线程池的 AbortPolicy（抛异常而非静默丢弃）才能走到这里；
            // 漏掉这个清理会导致该短码在本实例内永久无法刷新（标记泄漏）
            refreshingCodes.remove(code);
            log.warn("缓存刷新任务被拒绝（队列已满），跳过本次刷新: code={}", code);
        } catch (Exception e) {
            // 接口约定：刷新是旁路能力，任何异常都不得影响调用方（跳转主链路）
            refreshingCodes.remove(code);
            log.error("提交缓存刷新任务异常，跳过本次刷新: code={}", code, e);
        }
    }

    /**
     * 实际刷新：抢到分布式锁才回源，避免多实例同时重建
     */
    void doRefresh(String code) {
        if (!rebuildLock.tryLock(code)) {
            log.debug("已有其他实例在重建该短码缓存，跳过: code={}", code);
            return;
        }
        try {
            String key = CacheKeyBuilder.buildLinkKey(properties.getDomain(), code);
            LinkDO link = linkMapper.selectOne(new QueryWrapper<LinkDO>()
                    .eq("code", code)
                    .eq("domain", properties.getDomain())
                    .last("LIMIT 1"));
            if (link == null) {
                // 短链已被删除：写空值标记，让后续请求快速 404（而不是每次都回源）；
                // 同时清掉本实例 L1，否则本地旧值会继续把已删除的短链跳出去
                stringRedisTemplate.opsForValue().set(key, CacheKeyBuilder.NULL_MARKER,
                        Duration.ofSeconds(properties.getCache().getNullTtlSeconds()));
                localLinkCache.invalidate(key);
                return;
            }
            LinkCacheDTO dto = LinkCacheAssembler.toCacheDTO(link,
                    properties.getCache().getAntiBreakdown().getLogicalExpireSeconds());
            long ttl = CacheKeyBuilder.jitteredTtlSeconds(properties.getCache().getTtlSeconds(),
                    properties.getCache().getJitterSeconds());
            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(dto),
                    Duration.ofSeconds(ttl));
            // 刷新本实例 L1（其他实例的 L1 由各自 TTL 过期，避免为此再发一轮广播）
            localLinkCache.put(key, dto);
            log.debug("短码缓存异步刷新完成: code={}", code);
        } catch (Exception e) {
            // 刷新失败保留旧值，等下一次逻辑过期触发或物理 TTL 到期后回源
            log.error("短码缓存异步刷新失败（保留旧值）: code={}", code, e);
        } finally {
            rebuildLock.unlock(code);
        }
    }
}
