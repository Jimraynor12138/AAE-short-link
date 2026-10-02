package com.shortlink.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.shortlink.cache.CacheKeyBuilder;
import com.shortlink.config.ShortLinkProperties;
import com.shortlink.dto.StatsRespDTO;
import com.shortlink.service.StatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 访问统计实现（V1.3：Redis 同步统计）。
 *
 * 数据结构选型：
 * - PV 用 String INCR：原子自增，单 key 计数
 * - UV / 独立 IP 用 HyperLogLog（PFADD/PFCOUNT）：固定约 12KB/key 即可做海量基数估算，
 *   误差约 0.81%；若用 Set 存原始值，一个热点短码一天就能吃掉大量内存 —— 这是典型取舍点
 *
 * 注意：本实现位于跳转链路内（同步），会增加约 3 次 Redis RTT，
 * V2 引入 MQ 后将整体异步化（这正是 V2 的动机）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsServiceImpl implements StatsService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StringRedisTemplate stringRedisTemplate;
    private final ShortLinkProperties properties;

    @Override
    public void recordVisit(String code, String clientIp, String userAgent) {
        try {
            String date = LocalDate.now().format(DATE_FORMATTER);
            String pvKey = CacheKeyBuilder.buildPvKey(date, code);

            // PV：原子自增；返回值为 1 说明是该 key 今天第一次写入，顺手设置过期（省一次 RTT）
            Long pv = stringRedisTemplate.opsForValue().increment(pvKey);
            if (pv != null && pv == 1L) {
                long ttl = properties.getCache().getStatsTtlSeconds();
                stringRedisTemplate.expire(pvKey, Duration.ofSeconds(ttl));
                stringRedisTemplate.expire(CacheKeyBuilder.buildUvKey(date, code), Duration.ofSeconds(ttl));
                stringRedisTemplate.expire(CacheKeyBuilder.buildIpKey(date, code), Duration.ofSeconds(ttl));
            }

            // UV：访客身份用 ip + UA 摘要（同一 IP 换设备应算两个访客）
            String visitorId = DigestUtil.md5Hex(StrUtil.blankToDefault(clientIp, "unknown")
                    + "|" + StrUtil.blankToDefault(userAgent, "unknown"));
            stringRedisTemplate.opsForHyperLogLog().add(CacheKeyBuilder.buildUvKey(date, code), visitorId);

            // 独立 IP
            stringRedisTemplate.opsForHyperLogLog().add(
                    CacheKeyBuilder.buildIpKey(date, code), StrUtil.blankToDefault(clientIp, "unknown"));
        } catch (Exception e) {
            // 统计属于旁路能力：任何异常都不能影响跳转主链路
            log.error("访问统计写入失败, code={}", code, e);
        }
    }

    @Override
    public StatsRespDTO queryStats(String code, String date) {
        String statDate = StrUtil.isBlank(date) ? LocalDate.now().format(DATE_FORMATTER) : date;

        StatsRespDTO resp = new StatsRespDTO();
        resp.setCode(code);
        resp.setDate(statDate);

        String pvValue = stringRedisTemplate.opsForValue().get(CacheKeyBuilder.buildPvKey(statDate, code));
        resp.setPv(pvValue == null ? 0L : Long.parseLong(pvValue));

        Long uv = stringRedisTemplate.opsForHyperLogLog().size(CacheKeyBuilder.buildUvKey(statDate, code));
        Long ipCnt = stringRedisTemplate.opsForHyperLogLog().size(CacheKeyBuilder.buildIpKey(statDate, code));
        resp.setUv(uv == null ? 0L : uv);
        resp.setIpCnt(ipCnt == null ? 0L : ipCnt);
        return resp;
    }
}
