package com.shortlink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortlink.cache.CacheKeyBuilder;
import com.shortlink.common.util.RefererParser;
import com.shortlink.config.ShortLinkProperties;
import com.shortlink.dao.LinkMapper;
import com.shortlink.dao.LinkStatsMapper;
import com.shortlink.entity.LinkDO;
import com.shortlink.entity.LinkStatsDO;
import com.shortlink.service.StatsSnapshotService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统计快照落库实现（V2）：内存脏标记 + 定时批量 upsert。
 *
 * 为什么不是「每条消息写一次库」：
 * 跳转量高时统计消息量同样高，逐条写库会把 MySQL 写爆（这正是 V1 压测里
 * 同步统计导致吞吐下降 30%+ 的同类问题，只是搬到了消费端）。
 * 批量 + 快照覆盖后，DB 写入量降为「每 2 秒 × 活跃短码数」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsSnapshotServiceImpl implements StatsSnapshotService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 兜底扫描的固定间隔（10 分钟）。
     *
     * 注意：这里刻意用「编译期常量」而不是从配置读，因为 @Scheduled(fixedDelayString=...)
     * 若被配成 0，会变成 0 延迟的高频空转，把单线程调度器占满、连带拖慢 flush()。
     * 因此「关闭」语义由配置的布尔开关控制，间隔固定。
     */
    private static final long RESCAN_DELAY_MS = 600_000L;

    /**
     * 脏数据键：短码 + 统计日期。
     * 必须带日期：0 点前后的 flush 若统一用「当前日期」，前一天的脏数据会被当成
     * 「今天没有数据」而丢弃（跨零点丢统计）
     */
    public record DirtyKey(String code, LocalDate date) {
    }

    /** 脏数据集合：消费端标记，定时任务消费 */
    private final Set<DirtyKey> dirtyKeys = ConcurrentHashMap.newKeySet();

    private final StringRedisTemplate stringRedisTemplate;
    private final LinkMapper linkMapper;
    private final LinkStatsMapper linkStatsMapper;
    private final ObjectMapper objectMapper;
    private final ShortLinkProperties properties;

    @Override
    public void markDirty(String code, LocalDate date) {
        dirtyKeys.add(new DirtyKey(code, date));
    }

    @Scheduled(fixedDelayString = "${shortlink.stats-persist.flush-interval-ms:2000}")
    @Override
    public void flush() {
        if (dirtyKeys.isEmpty()) {
            return;
        }
        List<DirtyKey> batch = new ArrayList<>(dirtyKeys);
        int limit = Math.min(batch.size(), properties.getStatsPersist().getMaxBatchSize());
        List<DirtyKey> toFlush = batch.subList(0, limit);

        // 批量解析 link_id：一次 IN 查询代替每个短码一次查询
        Map<String, Long> linkIdMap = resolveLinkIds(toFlush.stream().map(DirtyKey::code).distinct().toList());

        int success = 0;
        for (DirtyKey key : toFlush) {
            try {
                flushOne(key, linkIdMap);
            } catch (Exception e) {
                // 单条失败不影响其他短码；保留脏标记，下一轮重试
                log.error("统计快照落库失败: code={}, date={}", key.code(), key.date(), e);
                continue;
            }
            dirtyKeys.remove(key);
            success++;
        }
        if (success > 0) {
            log.debug("统计快照落库完成: count={}", success);
        }
    }

    /**
     * 扫描 Redis 当日统计 key，把所有「有统计数据的短码」重新标记为脏。
     *
     * 存在的意义：脏标记是内存态，进程重启/宕机后丢失 —— 消息已 ack、Redis 里还有数据，
     * 但没有任何组件会再触发落库。启动时与定时兜底扫描可以自愈这类漏写。
     *
     * @return 本次重新标记的短码数
     */
    @Override
    public int rescan() {
        LocalDate date = LocalDate.now();
        String pattern = CacheKeyBuilder.buildPvKey(date.format(DATE_FORMATTER), "*");
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(500).build();
        int marked = 0;
        // SCAN 是游标式遍历，不会像 KEYS 那样阻塞 Redis
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            while (cursor.hasNext() && marked < properties.getStatsPersist().getMaxBatchSize()) {
                String key = cursor.next();
                String code = key.substring(key.lastIndexOf(':') + 1);
                if (!code.isBlank()) {
                    dirtyKeys.add(new DirtyKey(code, date));
                    marked++;
                }
            }
        } catch (Exception e) {
            log.error("统计 key 兜底扫描失败", e);
        }
        if (marked > 0) {
            log.info("统计 key 兜底扫描完成: date={}, marked={}", date, marked);
        }
        return marked;
    }

    /**
     * 启动时兜底扫描一次：自愈「重启丢脏标记导致的漏写」
     */
    @PostConstruct
    public void rescanOnStartup() {
        try {
            rescan();
        } catch (Exception e) {
            log.error("启动兜底扫描失败", e);
        }
    }

    /**
     * 定时兜底扫描：间隔固定 10 分钟，可用 shortlink.stats-persist.rescan-enabled=false 关闭
     */
    @Scheduled(fixedDelay = RESCAN_DELAY_MS)
    public void scheduledRescan() {
        if (!properties.getStatsPersist().isRescanEnabled()) {
            return;
        }
        rescan();
    }

    /**
     * 落库单个短码某日的快照；短链已不存在或统计 key 已过期时直接返回（无需重试）
     */
    private void flushOne(DirtyKey dirtyKey, Map<String, Long> linkIdMap) throws Exception {
        String code = dirtyKey.code();
        String date = dirtyKey.date().format(DATE_FORMATTER);

        String pvValue = stringRedisTemplate.opsForValue().get(CacheKeyBuilder.buildPvKey(date, code));
        if (pvValue == null) {
            // 统计 key 已过期或从未写入，无需落库
            return;
        }

        Long linkId = linkIdMap.get(code);
        if (linkId == null) {
            log.warn("短链已不存在，跳过统计落库: code={}", code);
            return;
        }

        LinkStatsDO stats = new LinkStatsDO();
        stats.setLinkId(linkId);
        stats.setCode(code);
        stats.setDate(dirtyKey.date());
        stats.setPv(Long.parseLong(pvValue));

        Long uv = stringRedisTemplate.opsForHyperLogLog().size(CacheKeyBuilder.buildUvKey(date, code));
        Long ipCnt = stringRedisTemplate.opsForHyperLogLog().size(CacheKeyBuilder.buildIpKey(date, code));
        stats.setUv(uv == null ? 0L : uv);
        stats.setIpCnt(ipCnt == null ? 0L : ipCnt);

        Map<Object, Object> refererMap = stringRedisTemplate.opsForHash()
                .entries(CacheKeyBuilder.buildRefererKey(date, code));
        Map<Object, Object> deviceMap = stringRedisTemplate.opsForHash()
                .entries(CacheKeyBuilder.buildDeviceKey(date, code));
        stats.setRefererStats(objectMapper.writeValueAsString(refererMap));
        stats.setDeviceStats(objectMapper.writeValueAsString(deviceMap));
        Object direct = refererMap.get(RefererParser.DIRECT);
        stats.setDefaultCnt(direct == null ? 0L : Long.parseLong(String.valueOf(direct)));

        linkStatsMapper.upsertStats(stats);
    }

    /**
     * 批量由短码解析短链 ID：一次 IN 查询代替「每个短码一次查询」
     */
    private Map<String, Long> resolveLinkIds(List<String> codes) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        List<LinkDO> links = linkMapper.selectList(new QueryWrapper<LinkDO>()
                .select("id", "code")
                .eq("domain", properties.getDomain())
                .in("code", codes));
        Map<String, Long> result = new HashMap<>(links.size());
        for (LinkDO link : links) {
            result.put(link.getCode(), link.getId());
        }
        return result;
    }

    /**
     * 应用关闭前把残留的脏数据落库，避免停机丢统计
     */
    @PreDestroy
    public void flushOnShutdown() {
        try {
            flush();
        } catch (Exception e) {
            log.error("停机前统计落库失败", e);
        }
    }
}
