package com.shortlink.idgenerator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 号段分配器（V3.5）：负责「一次拿一段号」这一步 DB 交互。
 *
 * 独立成 Bean（而不是放在 {@link SegmentIdGenerator} 里）有两个原因：
 * 1. `@Transactional` 靠 Spring 代理生效，**同类内部自调用不会走代理**（经典坑）；
 *    双 buffer 的预取发生在后台线程里，必须经由代理才能拿到事务语义
 * 2. 让发号器本体只关心"内存里怎么发号"，DB 交互单独可测
 *
 * 为什么需要事务：`UPDATE ... SET max_id = max_id + step` 会持有该行的排他锁直到事务提交，
 * 同事务内的 `SELECT max_id` 才能安全读到"我刚分配到的上界"；否则并发下两个实例可能读到同一个值。
 *
 * 另一个可选实现是 MySQL 的 `LAST_INSERT_ID(expr)` 技巧（UPDATE 内取值，省掉一次 SELECT），
 * 但它依赖"同一连接"（LAST_INSERT_ID 是连接级的），可读性与可测性都不如显式两步。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SegmentAllocator {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 原子分配一个号段
     *
     * @return 号段上界（本次可发放的最大号）
     */
    @Transactional(rollbackFor = Exception.class)
    public long allocate(String bizTag, int step) {
        ensureRow(bizTag, step);
        jdbcTemplate.update("UPDATE t_segment SET max_id = max_id + ? WHERE biz_tag = ?", step, bizTag);
        Long maxId = jdbcTemplate.queryForObject(
                "SELECT max_id FROM t_segment WHERE biz_tag = ?", Long.class, bizTag);
        if (maxId == null) {
            throw new IllegalStateException("号段分配失败：读取 max_id 为空, bizTag=" + bizTag);
        }
        return maxId;
    }

    /**
     * 把号段的 max_id 抬升到不小于 minValue（只增不减）。
     *
     * 用途：与其它发号器（DB 自增 / Redis INCR / 雪花）切换时，把号段起点对齐到
     * 数据库已用过的最大号之上，避免重号；也用于短码冲突后的自愈
     */
    @Transactional(rollbackFor = Exception.class)
    public void raiseTo(String bizTag, int step, long minValue) {
        ensureRow(bizTag, step);
        jdbcTemplate.update("UPDATE t_segment SET max_id = GREATEST(max_id, ?) WHERE biz_tag = ?",
                minValue, bizTag);
    }

    /**
     * 确保号段行存在（首次部署或行被误删时自建）。
     *
     * 用 `SELECT COUNT(1)` 探测而不是 `UPDATE ... SET max_id = max_id`：
     * MySQL 的 affected-rows 是**实际改变的行数**，行存在时那条空更新也返回 0，
     * 会让"不存在"的判断永远成立（每次分配都白跑一条 INSERT IGNORE）。
     * 这里用只读探测，顺带避免每次都拿一次行锁。
     */
    private void ensureRow(String bizTag, int step) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM t_segment WHERE biz_tag = ?", Integer.class, bizTag);
        if (count == null || count == 0) {
            jdbcTemplate.update("INSERT IGNORE INTO t_segment(biz_tag, max_id, step) VALUES (?, 0, ?)",
                    bizTag, step);
        }
    }
}
