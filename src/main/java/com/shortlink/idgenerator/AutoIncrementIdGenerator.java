package com.shortlink.idgenerator;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * V0 发号器：数据库自增 ID。
 *
 * <p>实现方式：向 t_sequence 插入占位行，通过 RETURN_GENERATED_KEYS 取回自增值。
 *
 * <p>已知局限（正是后续版本要解决的问题）：
 * <ul>
 *   <li>高并发下自增主键的锁竞争与单库写入上限，会成为创建链路瓶颈</li>
 *   <li>强依赖 DB 可用性：DB 挂则发号全停</li>
 *   <li>号连续，短码可被按序遍历（爬虫可扫描全部短链）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "shortlink.id-generator-type", havingValue = "auto", matchIfMissing = true)
public class AutoIncrementIdGenerator implements IdGenerator {

    private static final String INSERT_SQL = "INSERT INTO t_sequence(stub) VALUES (1)";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 启动时把序列表的自增起点对齐到 t_link 的实际进度。
     *
     * 为什么需要：t_link.id 才是真实的 id 空间。若中途切换过发号器
     * （例如先用 Redis 发号、后又切回 DB 自增），t_sequence 的计数会**远远落后**于
     * t_link 的最大 id，于是新号全是已用过的号，创建会连续冲突。
     * ALTER TABLE ... AUTO_INCREMENT 只能抬高、不会调低，天然是单调的。
     */
    @PostConstruct
    public void alignSequenceWithLinks() {
        long linkMaxId = queryLinkMaxId();
        if (linkMaxId > 0) {
            jdbcTemplate.execute("ALTER TABLE t_sequence AUTO_INCREMENT = " + (linkMaxId + 1));
            log.info("DB 自增发号器已与 t_link 对齐: AUTO_INCREMENT={}", linkMaxId + 1);
        }
    }

    @Override
    public void recoverAfterConflict() {
        // 冲突即说明序列表落后，重新对齐后再由上层重试
        alignSequenceWithLinks();
    }

    private long queryLinkMaxId() {
        Long maxId = jdbcTemplate.queryForObject("SELECT IFNULL(MAX(id), 0) FROM t_link", Long.class);
        return maxId == null ? 0L : maxId;
    }

    @Override
    public long nextId() {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("数据库自增发号失败：未取回生成的主键");
        }
        return key.longValue();
    }
}
