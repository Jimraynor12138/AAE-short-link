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
     * 启动时播种：仅当 key 不存在时写入 DB 当前最大 id，避免重启后从 1 开始造成重号
     */
    @PostConstruct
    public void seedIfAbsent() {
        long dbMaxId = queryDbMaxId();
        Boolean absent = stringRedisTemplate.opsForValue().setIfAbsent(ID_KEY, String.valueOf(dbMaxId));
        if (Boolean.TRUE.equals(absent)) {
            log.info("Redis 发号器已播种: key={}, seed={}", ID_KEY, dbMaxId);
        } else {
            log.info("Redis 发号器已存在，沿用当前值: key={}", ID_KEY);
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
