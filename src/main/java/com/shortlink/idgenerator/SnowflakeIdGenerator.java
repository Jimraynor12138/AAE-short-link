package com.shortlink.idgenerator;

import com.shortlink.config.ShortLinkProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.function.LongSupplier;

/**
 * V3.5 发号器：雪花算法（Snowflake）。
 *
 * ## 位结构（64 位 long）
 * <pre>
 * | 1 位符号(恒 0) | 41 位时间戳(毫秒) | 10 位机器 ID | 12 位序列号 |
 * </pre>
 * - 41 位时间戳：从「自定义纪元」起约 69 年
 * - 10 位机器 ID：1024 台实例
 * - 12 位序列号：同一毫秒内每台机器 4096 个号 → 单机理论上限约 409.6 万 ID/秒
 *
 * ## 特点与代价（本项目用它做对比学习的重点）
 * - 优点：**完全本地生成**，不查 Redis、不查 DB，没有网络往返，性能天花板最高
 * - 代价 1：ID 数值远大于自增/号段 → Base62 后约 10~11 位短码（号段只有 1~3 位）。
 *   所以真实短链系统更偏爱号段/自增，Snowflake 更适合订单号这类"不需要短"的场景。
 *   缓解手段：用较晚的**自定义纪元**（本项目默认 2024-01-01）显著缩小数值
 * - 代价 2：**时钟回拨**必须处理（见 {@link #handleClockBackwards}）
 * - 代价 3：**机器 ID 分配**需要基础设施（配置中心/ZK/K8s 序号），本项目用配置项模拟
 *
 * ## 并发
 * `nextId` 用实例内的 synchronized 串行化：序列号是共享可变状态，这是雪花算法的常规做法
 * （单机上限 400 万/秒，远高于短链创建的真实需求，加锁不是瓶颈）
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "shortlink.id-generator-type", havingValue = "snowflake")
public class SnowflakeIdGenerator implements IdGenerator {

    /** 机器 ID 位数 */
    private static final int WORKER_ID_BITS = 10;
    /** 序列号位数 */
    private static final int SEQUENCE_BITS = 12;

    /** 机器 ID 最大值（10 位 → 1023） */
    static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    /** 序列号掩码（12 位 → 4095） */
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private static final int WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    private final long workerId;
    private final long epoch;
    private final long maxBackwardMillis;

    /** 同一毫秒内的序列号 */
    private long sequence = 0L;
    /** 上次发号使用的时间戳（毫秒） */
    private long lastTimestamp = -1L;

    /**
     * 时钟来源：抽成字段便于单测构造「时钟回拨」「同毫秒序列号耗尽」等场景
     * （只测试会替换它，生产恒为 System.currentTimeMillis）
     */
    private LongSupplier clock = System::currentTimeMillis;

    public SnowflakeIdGenerator(ShortLinkProperties properties) {
        ShortLinkProperties.Snowflake config = properties.getSnowflake();
        if (config.getWorkerId() < 0 || config.getWorkerId() > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "shortlink.snowflake.worker-id 必须在 0~" + MAX_WORKER_ID + " 之间，当前=" + config.getWorkerId());
        }
        // 纪元必须不晚于当前时间：否则 (now - epoch) 为负 → ID 为负 → Base62Codec 直接抛异常，
        // 表现为"创建接口 500"，且错误信息与真正原因（配置写错）相距甚远。启动即失败更省事。
        if (config.getEpochMillis() > System.currentTimeMillis()) {
            throw new IllegalArgumentException(
                    "shortlink.snowflake.epoch-millis 不能晚于当前时间（否则 ID 为负），当前配置="
                            + config.getEpochMillis());
        }
        this.workerId = config.getWorkerId();
        this.epoch = config.getEpochMillis();
        this.maxBackwardMillis = config.getMaxBackwardMillis();
        log.info("雪花发号器就绪: workerId={}, epoch={}, maxBackwardMillis={}",
                workerId, Instant.ofEpochMilli(epoch), maxBackwardMillis);
    }

    /**
     * 仅供测试：替换时钟来源
     */
    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public synchronized long nextId() {
        long now = clock.getAsLong();
        if (now < lastTimestamp) {
            now = handleClockBackwards(now);
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0L) {
                // 同一毫秒内 4096 个号用尽：自旋等到下一毫秒（这就是 12 位的硬上限）
                now = waitUntilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = now;
        return ((now - epoch) << TIMESTAMP_SHIFT) | (workerId << WORKER_ID_SHIFT) | sequence;
    }

    /**
     * 时钟回拨处理（雪花算法最常被问到的坑）。
     *
     * - 回拨幅度 ≤ maxBackwardMillis：**自旋等待时钟追上**（NTP 微调常见，通常几毫秒）
     * - 回拨幅度过大：**抛异常拒绝发号**（生成重复 ID 的代价远大于一次创建失败）
     */
    private long handleClockBackwards(long now) {
        long offset = lastTimestamp - now;
        if (offset <= maxBackwardMillis) {
            log.warn("检测到时钟回拨 {}ms（≤{}ms），自旋等待追上", offset, maxBackwardMillis);
            long deadline = System.currentTimeMillis() + offset + maxBackwardMillis;
            while (clock.getAsLong() < lastTimestamp) {
                Thread.onSpinWait();
                if (System.currentTimeMillis() > deadline) {
                    throw new ClockBackwardsException(
                            "时钟回拨等待超时，已拒绝发号: offset=" + offset + "ms");
                }
            }
            return clock.getAsLong();
        }
        log.error("时钟回拨过大（{}ms > {}ms），拒绝发号以避免重复 ID；请检查机器时钟同步",
                offset, maxBackwardMillis);
        throw new ClockBackwardsException("检测到时钟回拨 " + offset + "ms，超过允许上限 "
                + maxBackwardMillis + "ms，已拒绝发号");
    }

    /**
     * 自旋等到下一毫秒
     */
    private long waitUntilNextMillis(long lastMillis) {
        long now = clock.getAsLong();
        while (now <= lastMillis) {
            Thread.onSpinWait();
            now = clock.getAsLong();
        }
        return now;
    }
}
