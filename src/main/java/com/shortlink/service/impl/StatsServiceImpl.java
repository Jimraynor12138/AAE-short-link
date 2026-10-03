package com.shortlink.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.shortlink.cache.CacheKeyBuilder;
import com.shortlink.common.util.RefererParser;
import com.shortlink.common.util.UserAgentParser;
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
import java.util.HashMap;
import java.util.Map;

/**
 * 访问统计实现：Redis 实时口径。
 *
 * 数据结构选型：
 * - PV 用 String INCR（原子自增）
 * - UV / 独立 IP 用 HyperLogLog（约 12KB/key 做海量基数估算，误差约 0.81%）
 * - 来源 / 设备分布用 Hash（HINCRBY），基数小、需精确计数
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsServiceImpl implements StatsService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StringRedisTemplate stringRedisTemplate;
    private final ShortLinkProperties properties;

    @Override
    public void recordVisit(String code, String clientIp, String userAgent, String referer) {
        try {
            doRecordVisit(code, clientIp, userAgent, referer, LocalDate.now());
        } catch (Exception e) {
            // 跳转线程内调用：统计是旁路能力，任何异常都不能影响跳转主链路
            log.error("访问统计写入失败（已忽略，不影响跳转）, code={}", code, e);
        }
    }

    @Override
    public void recordVisitStrict(String code, String clientIp, String userAgent, String referer, LocalDate visitDate) {
        // 消费端调用：必须让异常冒泡，否则消息被正常 ack，重试与死信队列全部失效
        doRecordVisit(code, clientIp, userAgent, referer, visitDate == null ? LocalDate.now() : visitDate);
    }

    /**
     * 实际写入逻辑：本方法不吞异常，由调用方决定「忽略」还是「抛出让消息重试」
     */
    private void doRecordVisit(String code, String clientIp, String userAgent, String referer, LocalDate visitDate) {
        String date = visitDate.format(DATE_FORMATTER);
        String pvKey = CacheKeyBuilder.buildPvKey(date, code);

        // PV：原子自增；返回 1 说明是该 key 今天第一次写入，顺手设置全部统计 key 的过期时间
        Long pv = stringRedisTemplate.opsForValue().increment(pvKey);
        if (pv != null && pv == 1L) {
            long ttl = properties.getCache().getStatsTtlSeconds();
            stringRedisTemplate.expire(pvKey, Duration.ofSeconds(ttl));
            stringRedisTemplate.expire(CacheKeyBuilder.buildUvKey(date, code), Duration.ofSeconds(ttl));
            stringRedisTemplate.expire(CacheKeyBuilder.buildIpKey(date, code), Duration.ofSeconds(ttl));
            stringRedisTemplate.expire(CacheKeyBuilder.buildRefererKey(date, code), Duration.ofSeconds(ttl));
            stringRedisTemplate.expire(CacheKeyBuilder.buildDeviceKey(date, code), Duration.ofSeconds(ttl));
        }

        // UV：访客身份 = ip + UA 摘要（同一 IP 换设备算两个访客）
        String visitorId = DigestUtil.md5Hex(blankToUnknown(clientIp) + "|" + blankToUnknown(userAgent));
        stringRedisTemplate.opsForHyperLogLog().add(CacheKeyBuilder.buildUvKey(date, code), visitorId);

        // 独立 IP
        stringRedisTemplate.opsForHyperLogLog().add(CacheKeyBuilder.buildIpKey(date, code), blankToUnknown(clientIp));

        // 来源分布（无 Referer 记为 direct）
        stringRedisTemplate.opsForHash().increment(CacheKeyBuilder.buildRefererKey(date, code),
                RefererParser.parseHost(referer), 1L);

        // 设备分布
        stringRedisTemplate.opsForHash().increment(CacheKeyBuilder.buildDeviceKey(date, code),
                UserAgentParser.parse(userAgent), 1L);
    }

    @Override
    public StatsRespDTO queryStats(String code, String date) {
        String statDate = StrUtil.isBlank(date) ? LocalDate.now().format(DATE_FORMATTER) : date;

        StatsRespDTO resp = new StatsRespDTO();
        resp.setCode(code);
        resp.setDate(statDate);

        String pvValue = stringRedisTemplate.opsForValue().get(CacheKeyBuilder.buildPvKey(statDate, code));
        resp.setPv(parsePv(pvValue));

        Long uv = stringRedisTemplate.opsForHyperLogLog().size(CacheKeyBuilder.buildUvKey(statDate, code));
        Long ipCnt = stringRedisTemplate.opsForHyperLogLog().size(CacheKeyBuilder.buildIpKey(statDate, code));
        resp.setUv(uv == null ? 0L : uv);
        resp.setIpCnt(ipCnt == null ? 0L : ipCnt);

        resp.setRefererStats(toLongMap(stringRedisTemplate.opsForHash()
                .entries(CacheKeyBuilder.buildRefererKey(statDate, code))));
        resp.setDeviceStats(toLongMap(stringRedisTemplate.opsForHash()
                .entries(CacheKeyBuilder.buildDeviceKey(statDate, code))));
        return resp;
    }

    /**
     * 解析 PV 值：Redis 中的值被外部改坏（非数字）时兜底为 0，避免查询接口 500
     */
    private long parsePv(String pvValue) {
        if (pvValue == null) {
            return 0L;
        }
        try {
            return Long.parseLong(pvValue);
        } catch (NumberFormatException e) {
            log.warn("PV 值异常，按 0 处理: value={}", pvValue);
            return 0L;
        }
    }

    private Map<String, Long> toLongMap(Map<Object, Object> raw) {
        Map<String, Long> result = new HashMap<>();
        if (raw == null) {
            return result;
        }
        raw.forEach((k, v) -> {
            try {
                result.put(String.valueOf(k), Long.parseLong(String.valueOf(v)));
            } catch (NumberFormatException ignored) {
                // 脏数据直接跳过，不影响统计接口
            }
        });
        return result;
    }

    private String blankToUnknown(String value) {
        return StrUtil.isBlank(value) ? "unknown" : value;
    }
}
