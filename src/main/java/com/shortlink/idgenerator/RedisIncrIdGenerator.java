package com.shortlink.idgenerator;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * V1.2 发号器：Redis INCR。
 *
 * 相比 V0（每次创建 = 写 t_sequence + 写 t_link 两次 DB 写），本实现把发号降到
 * 一次 Redis 原子自增，DB 只承担业务落库，显著减少创建链路的 DB 压力。
 *
 * 必须正视的风险（Redis 发号的核心缺点）：
 * 1. Redis 是内存存储，重启/主从切换/FLUSHALL 都可能丢失计数 → 重新从 1 开始 → 重号
 * 2. 强依赖 Redis 可用性（Redis 挂则发号停）
 * 应对（本实现）：
 * - 启动时按 DB 当前最大 id 播种（SETNX，不覆盖已存在的值）
 * - 运行期若发现计数从 1 重新开始（key 曾被清空），按 DB 最大值重新播种，跳到安全区间
 * - 最终兜底：t_link 的 (domain, code) 唯一索引 + 创建链路的重试（见 LinkServiceImpl）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "shortlink.id-generator-type", havingValue = "redis")
public class RedisIncrIdGenerator implements IdGenerator {

    /** 发号器 key */
    public static final String ID_KEY = "short-link:id-generator";

    private final StringRedisTemplate stringRedisTemplate;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 启动时播种 / 自愈：把计数抬升到「不小于 DB 当前最大 id」。
     *
     * 注意这里不是简单的 SETNX：
     * - key 不存在 → 播种为 dbMax（避免从 1 开始重号）
     * - key 存在但**落后于 dbMax**（Redis 从旧快照恢复、被清空后重建等）→ 抬升到 dbMax
     * - key 已领先 → 不动
     * 抬升用 INCRBY 而不是 SET，保证操作单调（绝不会把计数改小、不会与并发 INCR 冲突）。
     */
    @PostConstruct
    public void seedIfAbsent() {
        long dbMaxId = queryDbMaxId();
        raiseCounterTo(dbMaxId);
        log.info("Redis 发号器就绪: key={}, dbMaxId={}, current={}", ID_KEY, dbMaxId, currentCounter());
    }

    @Override
    public void recoverAfterConflict() {
        // 短码冲突说明号源落后于 DB：抬升到 DB 最大值之上再重试
        long dbMaxId = queryDbMaxId();
        raiseCounterTo(dbMaxId);
        log.warn("短码冲突自愈：发号器已抬升至 {}(dbMaxId={})", currentCounter(), dbMaxId);
    }

    /**
     * 单调抬升计数到至少 minValue（INCRBY 保证只增不减）
     */
    private void raiseCounterTo(long minValue) {
        if (minValue <= 0) {
            return;
        }
        long current = currentCounter();
        if (current < minValue) {
            stringRedisTemplate.opsForValue().increment(ID_KEY, minValue - current);
        }
    }

    private long currentCounter() {
        String value = stringRedisTemplate.opsForValue().get(ID_KEY);
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("发号器计数异常，按 0 处理并按 DB 重新抬升: value={}", value);
            return 0L;
        }
    }

    @Override
    public long nextId() {
        Long next = stringRedisTemplate.opsForValue().increment(ID_KEY);
        if (next == null) {
            throw new IllegalStateException("Redis 发号失败：INCR 返回空");
        }
        if (next == 1L) {
            // 计数回到 1 说明 key 曾被清空（Redis 重启/FLUSHALL），可能与他人重号
            long dbMaxId = queryDbMaxId();
            if (dbMaxId > 0) {
                next = stringRedisTemplate.opsForValue().increment(ID_KEY, dbMaxId);
                log.warn("检测到 Redis 发号 key 丢失，已按 DB 最大值重新播种并跳过安全区间: dbMaxId={}", dbMaxId);
            }
        }
        return next;
    }

    /**
     * DB 当前最大短链 id：作为播种基准。
     * 用 t_link 而非 t_sequence，因为 t_link.id 才是真正被使用过的号，其之上的号可安全复用
     */
    private long queryDbMaxId() {
        Long maxId = jdbcTemplate.queryForObject("SELECT IFNULL(MAX(id), 0) FROM t_link", Long.class);
        return maxId == null ? 0L : maxId;
    }
}
