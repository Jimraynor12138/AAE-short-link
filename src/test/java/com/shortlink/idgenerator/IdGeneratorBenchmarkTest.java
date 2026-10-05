package com.shortlink.idgenerator;

import com.shortlink.config.ShortLinkProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 发号器横向对比基准（V3.5）。
 *
 * 默认**不执行**（单测要求不依赖外部环境），需要显式开启：
 * <pre>
 * mvn -B test -Dtest=IdGeneratorBenchmarkTest -Dshortlink.benchmark=true
 * </pre>
 *
 * 说明（避免误读数据）：
 * - 单线程串行测量「每次取号的耗时」，用于对比**不同方案的固有成本**（本地计算 / Redis RTT / DB RTT）
 * - 段分配与 DB 自增依赖本机 MySQL、Redis，不可用时自动跳过对应行并说明
 * - 每个用例都顺带断言 ID 唯一性：性能再好，重号也是不可接受的
 */
@EnabledIfSystemProperty(named = "shortlink.benchmark", matches = "true")
class IdGeneratorBenchmarkTest {

    private static final String DB_URL = System.getProperty("shortlink.benchmark.jdbc-url",
            "jdbc:mysql://localhost:3306/short_link?useUnicode=true&characterEncoding=utf8"
                    + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
    private static final String DB_USER = System.getProperty("shortlink.benchmark.db-user", "root");
    private static final String DB_PASSWORD = System.getProperty("shortlink.benchmark.db-password", "123456");

    @Test
    void compareGenerators() {
        StringBuilder report = new StringBuilder("\n=== 发号器横向对比（单线程，每号耗时）===\n");
        report.append(String.format("%-34s %12s %14s %10s%n", "方案", "总量", "总耗时(ms)", "每号(µs)"));

        // 1) 雪花：纯本地计算
        addRow(report, "snowflake(本地生成)", () -> {
            ShortLinkProperties properties = new ShortLinkProperties();
            return new SnowflakeIdGenerator(properties);
        }, 1_000_000);

        try (HikariDataSource dataSource = dataSource()) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            SegmentAllocator allocator = new SegmentAllocator(jdbcTemplate);

            // 2) 号段：内存发号路径（分配用假实现，只为测我们自己的缓冲/切换逻辑）
            SegmentAllocator fakeAllocator = mock(SegmentAllocator.class);
            AtomicLong fakeMax = new AtomicLong();
            // 注意：getArgument(1) 返回 Integer，必须显式转 long，
            // 否则编译器会把泛型推断成 Long，运行时抛 ClassCastException
            when(fakeAllocator.allocate(anyString(), anyInt()))
                    .thenAnswer(invocation -> fakeMax.addAndGet(((Number) invocation.getArgument(1)).longValue()));
            addRow(report, "segment(内存发号路径)", () -> {
                ShortLinkProperties properties = new ShortLinkProperties();
                properties.getSegment().setStep(1000);
                return new SegmentIdGenerator(fakeAllocator, properties, jdbcTemplate, Runnable::run);
            }, 1_000_000);

            // 3) 号段：真实 DB 分配摊销（每段 step 个号只付一次分配成本）
            int segments = 200;
            int step = segmentProperties().getSegment().getStep();
            long issued = (long) segments * step;
            SegmentIdGenerator realSegment = new SegmentIdGenerator(allocator,
                    segmentProperties(), jdbcTemplate, Runnable::run);
            Set<Long> realIds = new HashSet<>(1 << 19);
            long start = System.nanoTime();
            for (long i = 0; i < issued; i++) {
                realIds.add(realSegment.nextId());
            }
            long elapsed = System.nanoTime() - start;
            report.append(String.format("%-34s %12d %14.1f %10.2f%n",
                    "segment(含真实 DB 分配摊销, step=1000)", issued, elapsed / 1e6, elapsed / 1e3 / issued));
            assertEquals(issued, realIds.size(), "号段模式出现重复 ID");

            // 4) DB 自增：每个号一次 INSERT
            addRow(report, "db-auto-increment(每号一次 INSERT)", () -> new AutoIncrementIdGenerator(jdbcTemplate), 2_000);
        } catch (Exception e) {
            report.append("DB 相关方案执行失败（MySQL 不可用或 SQL 异常），已跳过: ")
                    .append(e.getMessage()).append('\n');
        }

        // 5) Redis INCR：每个号一次网络往返
        int redisOps = 50_000;
        try {
            LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 6379);
            factory.afterPropertiesSet();
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            try {
                RedisIncrIdGenerator redisGenerator = new RedisIncrIdGenerator(template, mock(JdbcTemplate.class));
                measure(report, "redis-incr(每号一次 RTT)", redisGenerator, redisOps);
            } finally {
                factory.destroy();
            }
        } catch (Exception e) {
            report.append("Redis 不可用，已跳过 redis-incr 方案: ").append(e.getMessage()).append('\n');
        }

        report.append("注：段分配摊销 = 一次号段分配的成本 / step；真实 QPS 还受并发与连接池影响。\n");
        System.out.println(report);
    }

    private HikariDataSource dataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(DB_URL);
        dataSource.setUsername(DB_USER);
        dataSource.setPassword(DB_PASSWORD);
        dataSource.setMaximumPoolSize(4);
        return dataSource;
    }

    private ShortLinkProperties segmentProperties() {
        ShortLinkProperties properties = new ShortLinkProperties();
        properties.getSegment().setStep(1000);
        properties.getSegment().setAllocRetryTimes(2);
        properties.getSegment().setAllocRetryIntervalMillis(20);
        return properties;
    }

    private void addRow(StringBuilder report, String name, Supplier<IdGenerator> supplier, int ops) {
        measure(report, name, supplier.get(), ops);
    }

    private void measure(StringBuilder report, String name, IdGenerator generator, int ops) {
        // 预热：让 JIT 编译与连接池完成初始化，避免把首次开销算进去
        int warmup = Math.min(ops / 10, 20_000);
        Set<Long> ids = new HashSet<>(Math.min(ops, 1_000_000));
        for (int i = 0; i < warmup; i++) {
            generator.nextId();
        }
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            ids.add(generator.nextId());
        }
        long elapsed = System.nanoTime() - start;

        // 只统计计时区间内产出的号（预热部分不入集合，所以这里断言 ops）
        report.append(String.format("%-34s %12d %14.1f %10.2f%n",
                name, ops, elapsed / 1e6, elapsed / 1e3 / ops));
        assertEquals(ops, ids.size(), name + " 出现重复 ID（性能再好也不能重号）");
    }
}
