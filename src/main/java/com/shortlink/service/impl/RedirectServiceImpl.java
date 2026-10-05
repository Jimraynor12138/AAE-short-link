package com.shortlink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortlink.bloom.ShortLinkBloomFilter;
import com.shortlink.cache.CacheKeyBuilder;
import com.shortlink.cache.CacheRebuildLock;
import com.shortlink.cache.DegradationGuard;
import com.shortlink.cache.LinkCacheAssembler;
import com.shortlink.cache.LocalLinkCache;
import com.shortlink.common.exception.DegradedException;
import com.shortlink.config.ShortLinkProperties;
import com.shortlink.dao.LinkMapper;
import com.shortlink.dto.LinkCacheDTO;
import com.shortlink.entity.LinkDO;
import com.shortlink.service.CacheRefreshService;
import com.shortlink.service.RedirectService;
import com.shortlink.service.VisitRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 短链跳转服务实现（V1.1：Redis Cache Aside + 空值缓存 + 降级）。
 *
 * 读路径：Redis 命中 → 应用侧校验停用/过期 → 返回
 * 未命中 → 查 MySQL → 回填缓存（TTL 抖动）→ 返回
 * 不存在 → 写空值缓存（短 TTL 防穿透）
 * Redis 异常 → 降级直查 MySQL（Redis 是加速层，不能因它故障导致跳转不可用）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedirectServiceImpl implements RedirectService {

    private final LinkMapper linkMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ShortLinkProperties properties;
    private final VisitRecorder visitRecorder;
    private final ShortLinkBloomFilter bloomFilter;
    private final CacheRefreshService cacheRefreshService;
    private final CacheRebuildLock rebuildLock;
    private final LocalLinkCache localLinkCache;
    private final DegradationGuard degradationGuard;

    @Override
    public String resolveRedirectUrl(String code, String clientIp, String userAgent, String referer) {
        // 0. 布隆过滤器前置拦截（V3.1 防穿透）：
        //    随机短码在这里就被判定「一定不存在」，连缓存都不用查 —— 比空值缓存更省。
        //    注意 fail-open 语义：过滤器不可用时 mightContain 返回 true（放行），绝不误拦正常短链。
        if (!bloomFilter.mightContain(code)) {
            return null;
        }

        String key = CacheKeyBuilder.buildLinkKey(properties.getDomain(), code);
        boolean cacheEnabled = properties.getCache().isEnabled();

        // 1. L1 本地缓存（V3.3，Caffeine）：热点短码不出进程，避开 Redis 网络往返与单 key 分片热点
        if (cacheEnabled) {
            LinkCacheDTO local = localLinkCache.get(key);
            if (local != null) {
                if (isLogicallyExpired(local)) {
                    triggerRefresh(code);
                }
                return redirectOrNull(local, code, clientIp, userAgent, referer);
            }
        }

        // 2. L2 Redis 缓存（命中则不碰 DB）。cache.enabled=false 时整段跳过，等价 V0 行为
        //    注意：try/catch 只包住真正的 Redis 读取，不能把「刷新触发 + 跳转校验」也包进去，
        //    否则刷新异常会被误判成「Redis 故障」并触发一次多余的回源（异常边界要贴住故障点）
        if (cacheEnabled) {
            String cached = null;
            try {
                cached = stringRedisTemplate.opsForValue().get(key);
                // Redis 可用 → 退出降级态（粘滞窗口内的降级状态在此被清除）
                degradationGuard.markHealthy();
            } catch (Exception e) {
                // Redis 故障：降级直查 DB（跳转可用性优先），同时进入降级态限制回源并发保护 DB
                log.error("Redis 读取异常，降级直查数据库, code={}", code, e);
                degradationGuard.markDegraded("redis-get:" + e.getClass().getSimpleName());
            }
            if (cached != null) {
                if (CacheKeyBuilder.NULL_MARKER.equals(cached)) {
                    // 空值缓存：该短码确认不存在
                    return null;
                }
                try {
                    LinkCacheDTO dto = objectMapper.readValue(cached, LinkCacheDTO.class);
                    // 回填 L1：L2 命中一次，后续热点请求都可以不出进程
                    localLinkCache.put(key, dto);
                    // V3.2 防击穿：逻辑过期后先返回旧值（跳转场景下 URL 基本不变，稍旧可接受），
                    // 同时触发后台异步重建 —— 请求线程零阻塞，不会因热点码过期而集体压向 DB
                    if (isLogicallyExpired(dto)) {
                        triggerRefresh(code);
                    }
                    return redirectOrNull(dto, code, clientIp, userAgent, referer);
                } catch (JsonProcessingException e) {
                    // 缓存值损坏（如手工改过/版本升级字段变更）：不直接失败，回源查询
                    log.warn("缓存值反序列化失败，回源查询, key={}", key, e);
                }
            }
        }

        // 3. 未命中/降级/缓存关闭 → 回源 DB（所有回源都要过降级闸门）
        //    V3.2 互斥重建：未命中时只允许一个请求回源构建，其余请求短暂等待其回填，
        //    避免热点短码在缓存失效瞬间被并发打穿（击穿）
        boolean mutexEnabled = cacheEnabled && properties.getCache().getAntiBreakdown().isMutexRebuildEnabled();
        if (mutexEnabled && !rebuildLock.tryLock(code)) {
            // 未抢到锁：说明已有请求在回源构建，本次「跟车」等待其回填
            log.debug("缓存未命中且未抢到重建锁，等待 leader 回填: code={}", code);
            String filled = waitForRebuild(key);
            if (CacheKeyBuilder.NULL_MARKER.equals(filled)) {
                // leader 已确认该短码不存在
                return null;
            }
            LinkCacheDTO filledDto = parseCacheValue(filled);
            if (filledDto != null) {
                // 复用 leader 回填的缓存；本轮不再触发刷新，避免刚回填就被判为逻辑过期
                log.debug("跟车成功，复用 leader 回填的缓存: code={}", code);
                return redirectOrNull(filledDto, code, clientIp, userAgent, referer);
            }
            // 等待超时或值损坏（leader 慢/异常）：兜底自己回源，但不再回填，避免写放大
            log.warn("等待缓存重建超时，回源兜底且不回填（leader 可能异常或过慢）: code={}", code);
            LinkDO fallback = findByCodeWithPermit(code);
            return fallback == null ? null
                    : redirectOrNull(toCacheDTO(fallback), code, clientIp, userAgent, referer);
        }

        try {
            LinkDO link = findByCodeWithPermit(code);
            if (link == null) {
                // 4. 空值缓存防穿透（短 TTL：避免刚创建的短码被空值挡住）
                if (cacheEnabled) {
                    safeSetCache(key, CacheKeyBuilder.NULL_MARKER, properties.getCache().getNullTtlSeconds());
                }
                return null;
            }

            // 5. 回填两级缓存：缓存数据快照（非结论），TTL 加随机抖动防集体过期
            LinkCacheDTO dto = toCacheDTO(link);
            if (cacheEnabled) {
                try {
                    String json = objectMapper.writeValueAsString(dto);
                    long ttl = CacheKeyBuilder.jitteredTtlSeconds(
                            properties.getCache().getTtlSeconds(),
                            properties.getCache().getJitterSeconds());
                    safeSetCache(key, json, ttl);
                    localLinkCache.put(key, dto);
                } catch (JsonProcessingException e) {
                    log.warn("缓存值序列化失败，跳过回填, code={}", code, e);
                }
            }

            // 6. 每次读取时应用侧校验（停用/过期以最新时间为准）
            return redirectOrNull(dto, code, clientIp, userAgent, referer);
        } finally {
            // 只有真正抢到锁的请求才负责解锁（跟车路径不能误删 leader 的锁）
            if (mutexEnabled) {
                rebuildLock.unlock(code);
            }
        }
    }

    /**
     * 校验通过则记录访问并返回目标 URL，否则返回 null（停用/过期不统计）
     */
    private String redirectOrNull(LinkCacheDTO dto, String code, String clientIp, String userAgent, String referer) {
        if (!isRedirectable(dto)) {
            return null;
        }
        // stats-enabled=false 时跳过统计（压测对比 / 故障降级用）
        if (properties.isStatsEnabled()) {
            // V2：默认只投递一条 MQ 消息就返回，统计处理不在跳转线程里做
            visitRecorder.record(code, clientIp, userAgent, referer);
        }
        return dto.getOriginalUrl();
    }

    /**
     * 写缓存：失败只记日志不抛出，缓存丢失由 TTL / 下次回源兜底
     */
    private void safeSetCache(String key, String value, long ttlSeconds) {
        try {
            stringRedisTemplate.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.error("Redis 写入异常, key={}", key, e);
        }
    }

    /**
     * 触发缓存异步重建（旁路能力，异常绝不能影响本次跳转）
     */
    private void triggerRefresh(String code) {
        try {
            cacheRefreshService.refreshAsync(code);
        } catch (Exception e) {
            log.warn("触发缓存异步刷新失败，本次继续返回旧值: code={}", code, e);
        }
    }

    /**
     * 带上「降级闸门」的回源查询（V3.3 防雪崩）。
     *
     * 跳转链路里所有 DB 查询都必须经过这里：正常态闸门直接放行（空许可），
     * 只有缓存层故障（降级态）时才会真正限量；拿不到许可就抛 {@link DegradedException}
     * 让请求快速失败（503），避免请求堆积把 DB 和线程池一起拖垮。
     */
    private LinkDO findByCodeWithPermit(String code) {
        DegradationGuard.Permit permit = degradationGuard.tryAcquirePermit();
        if (permit == null) {
            throw new DegradedException("服务繁忙，请稍后重试");
        }
        try (permit) {
            return findByCode(code);
        }
    }

    private LinkDO findByCode(String code) {
        // 字符串列名（而非 Lambda），保证单元测试无需初始化 MP 元数据缓存
        return linkMapper.selectOne(new QueryWrapper<LinkDO>()
                .eq("code", code)
                .eq("domain", properties.getDomain())
                .last("LIMIT 1"));
    }

    private LinkCacheDTO toCacheDTO(LinkDO link) {
        return LinkCacheAssembler.toCacheDTO(link,
                properties.getCache().getAntiBreakdown().getLogicalExpireSeconds());
    }

    /**
     * 缓存值是否已「逻辑过期」（V3.2）。
     *
     * 为兼容历史缓存值（无 logicalExpireAt 字段），null 一律视为未过期 —— 否则升级后
     * 所有存量缓存会被立刻判定过期，瞬间触发全量刷新（反而制造一次小的雪崩）。
     */
    boolean isLogicallyExpired(LinkCacheDTO dto) {
        return properties.getCache().getAntiBreakdown().isLogicalExpireEnabled()
                && dto.getLogicalExpireAt() != null
                && System.currentTimeMillis() > dto.getLogicalExpireAt();
    }

    /**
     * 解析缓存原始值
     *
     * @return null 表示无缓存、空值标记或值已损坏
     */
    private LinkCacheDTO parseCacheValue(String cached) {
        if (cached == null || CacheKeyBuilder.NULL_MARKER.equals(cached)) {
            return null;
        }
        try {
            return objectMapper.readValue(cached, LinkCacheDTO.class);
        } catch (JsonProcessingException e) {
            log.warn("缓存值反序列化失败", e);
            return null;
        }
    }

    /**
     * 互斥重建的「跟车」路径：轮询等待 leader 回填缓存
     *
     * @return 缓存原始值（可能是空值标记）；null 表示等待超时或读取异常
     */
    private String waitForRebuild(String key) {
        ShortLinkProperties.AntiBreakdown config = properties.getCache().getAntiBreakdown();
        long step = Math.max(1, config.getMutexWaitStepMillis());
        long waited = 0;
        while (waited < config.getMutexWaitMillis()) {
            try {
                Thread.sleep(step);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            waited += step;
            try {
                String cached = stringRedisTemplate.opsForValue().get(key);
                if (cached != null) {
                    return cached;
                }
            } catch (Exception e) {
                log.error("等待缓存重建期间读取异常, key={}", key, e);
                return null;
            }
        }
        return null;
    }

    /**
     * 跳转校验：启用状态 + 有效期。包内可见，便于单元测试直接覆盖各分支
     */
    boolean isRedirectable(LinkCacheDTO dto) {
        if (dto.getEnableStatus() != null && dto.getEnableStatus() != 0) {
            return false;
        }
        return !isExpired(dto);
    }

    /**
     * 是否已过期
     */
    boolean isExpired(LinkCacheDTO dto) {
        return dto.getValidType() != null && dto.getValidType() == 2
                && dto.getValidDate() != null
                && dto.getValidDate().isBefore(LocalDateTime.now());
    }
}
