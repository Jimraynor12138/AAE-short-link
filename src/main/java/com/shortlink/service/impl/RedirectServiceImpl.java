package com.shortlink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortlink.cache.CacheKeyBuilder;
import com.shortlink.config.ShortLinkProperties;
import com.shortlink.dao.LinkMapper;
import com.shortlink.dto.LinkCacheDTO;
import com.shortlink.entity.LinkDO;
import com.shortlink.service.RedirectService;
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

    @Override
    public String resolveRedirectUrl(String code) {
        String key = CacheKeyBuilder.buildLinkKey(properties.getDomain(), code);

        // 1. 查缓存（命中则不碰 DB）
        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null) {
                if (CacheKeyBuilder.NULL_MARKER.equals(cached)) {
                    // 空值缓存：该短码确认不存在
                    return null;
                }
                try {
                    LinkCacheDTO dto = objectMapper.readValue(cached, LinkCacheDTO.class);
                    return isRedirectable(dto) ? dto.getOriginalUrl() : null;
                } catch (JsonProcessingException e) {
                    // 缓存值损坏（如手工改过/版本升级字段变更）：不直接失败，回源查询
                    log.warn("缓存值反序列化失败，回源查询, key={}", key, e);
                }
            }
        } catch (Exception e) {
            // Redis 故障：降级直查 DB，跳转可用性优先
            log.error("Redis 读取异常，降级直查数据库, code={}", code, e);
        }

        // 2. 未命中/降级 → 查 DB
        LinkDO link = findByCode(code);
        if (link == null) {
            // 3. 空值缓存防穿透（短 TTL：避免刚创建的短码被空值挡住）
            safeSetCache(key, CacheKeyBuilder.NULL_MARKER, properties.getCache().getNullTtlSeconds());
            return null;
        }

        // 4. 回填缓存：缓存数据快照（非结论），TTL 加随机抖动防集体过期
        LinkCacheDTO dto = toCacheDTO(link);
        try {
            String json = objectMapper.writeValueAsString(dto);
            long ttl = CacheKeyBuilder.jitteredTtlSeconds(
                    properties.getCache().getTtlSeconds(),
                    properties.getCache().getJitterSeconds());
            safeSetCache(key, json, ttl);
        } catch (JsonProcessingException e) {
            log.warn("缓存值序列化失败，跳过回填, code={}", code, e);
        }

        // 5. 每次读取时应用侧校验（停用/过期以最新时间为准）
        return isRedirectable(dto) ? dto.getOriginalUrl() : null;
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

    private LinkDO findByCode(String code) {
        // 字符串列名（而非 Lambda），保证单元测试无需初始化 MP 元数据缓存
        return linkMapper.selectOne(new QueryWrapper<LinkDO>()
                .eq("code", code)
                .eq("domain", properties.getDomain())
                .last("LIMIT 1"));
    }

    private LinkCacheDTO toCacheDTO(LinkDO link) {
        LinkCacheDTO dto = new LinkCacheDTO();
        dto.setOriginalUrl(link.getOriginalUrl());
        dto.setEnableStatus(link.getEnableStatus());
        dto.setValidType(link.getValidType());
        dto.setValidDate(link.getValidDate());
        return dto;
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
