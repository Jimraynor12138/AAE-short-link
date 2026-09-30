package com.shortlink.idgenerator;

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
