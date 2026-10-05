package com.shortlink.idgenerator;

import com.shortlink.codec.Base62Codec;
import com.shortlink.config.ShortLinkProperties;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 雪花算法测试（V3.5）：唯一性、位段编码、序列号耗尽、时钟回拨两种处理
 */
class SnowflakeIdGeneratorTest {

    private static final int WORKER_ID_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);
    private static final long EPOCH = 1704067200000L;

    private ShortLinkProperties properties(long workerId, long maxBackwardMillis) {
        ShortLinkProperties properties = new ShortLinkProperties();
        properties.getSnowflake().setWorkerId(workerId);
        properties.getSnowflake().setEpochMillis(EPOCH);
        properties.getSnowflake().setMaxBackwardMillis(maxBackwardMillis);
        return properties;
    }

    private long sequenceOf(long id) {
        return id & SEQUENCE_MASK;
    }

    private long workerOf(long id) {
        return (id >>> SEQUENCE_BITS) & ((1L << WORKER_ID_BITS) - 1);
    }

    private long timestampOf(long id) {
        return (id >>> (SEQUENCE_BITS + WORKER_ID_BITS));
    }

    @Test
    void idsAreUniqueAndMonotonic() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(properties(1L, 5L));

        Set<Long> ids = new HashSet<>();
        long previous = -1;
        for (int i = 0; i < 10_000; i++) {
            long id = generator.nextId();
            assertTrue(id > previous, "雪花 ID 按时间趋势递增");
            previous = id;
            assertTrue(ids.add(id), "雪花 ID 不允许重复");
        }
    }

    @Test
    void workerIdAndSequenceAreEncodedInExpectedBits() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(properties(5L, 5L));

        long id = generator.nextId();

        assertEquals(5L, workerOf(id));
        assertEquals(0L, sequenceOf(id), "同一毫秒的第一个号序列号为 0");
        assertTrue(timestampOf(id) > 0, "时间戳部分应当大于 0");
    }

    @Test
    void sameMillisecondIncrementsSequence() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(properties(1L, 5L));
        long fixed = EPOCH + 1000L;
        generator.setClock(() -> fixed);

        long first = generator.nextId();
        long second = generator.nextId();
        long third = generator.nextId();

        assertEquals(0L, sequenceOf(first));
        assertEquals(1L, sequenceOf(second));
        assertEquals(2L, sequenceOf(third));
        assertEquals(fixed - EPOCH, timestampOf(second));
    }

    @Test
    void sequenceOverflowWaitsForNextMillisecond() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(properties(1L, 5L));
        long fixed = EPOCH + 2000L;
        // 前 4097 次读时钟都返回同一毫秒，之后进入下一毫秒（模拟自旋等待期间时间前进）
        AtomicLong reads = new AtomicLong();
        generator.setClock(() -> reads.incrementAndGet() > 4097 ? fixed + 1 : fixed);

        // 同一毫秒内发满 4096 个号（序列号 0~4095）
        for (int i = 0; i < 4096; i++) {
            generator.nextId();
        }
        // 第 4097 个：序列号回绕为 0 → 自旋等到下一毫秒
        long overflowed = generator.nextId();

        assertEquals(fixed + 1 - EPOCH, timestampOf(overflowed), "序列号用尽后必须进入下一毫秒");
        assertEquals(0L, sequenceOf(overflowed));
    }

    @Test
    void smallClockBackwardsSpinsUntilCaughtUp() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(properties(1L, 5L));
        long base = EPOCH + 3000L;
        AtomicLong reads = new AtomicLong();
        // 第 1 次读：正常时间；第 2 次读：回拨 3ms（≤ 上限）；之后时钟追上并前进
        LongSupplier clock = () -> {
            long n = reads.incrementAndGet();
            if (n == 2) {
                return base - 3;
            }
            return n >= 3 ? base + 3 : base;
        };
        generator.setClock(clock);

        long first = generator.nextId();
        long second = generator.nextId();

        assertTrue(second > first, "小幅回拨自旋等待后仍应生成更大的 ID");
    }

    @Test
    void largeClockBackwardsIsRejected() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(properties(1L, 5L));
        long base = EPOCH + 4000L;
        AtomicLong reads = new AtomicLong();
        generator.setClock(() -> reads.getAndIncrement() == 0 ? base : base - 100);

        generator.nextId();

        // 大幅回拨必须拒绝发号：宁可创建失败，也不能出重复 ID
        ClockBackwardsException e = assertThrows(ClockBackwardsException.class, generator::nextId);
        assertTrue(e.getMessage().contains("时钟回拨"));
    }

    @Test
    void invalidWorkerIdIsRejectedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(properties(2000L, 5L)));
    }

    @Test
    void futureEpochIsRejectedAtStartup() {
        // 回归（V3.5 审查）：纪元晚于当前时间会让 (now - epoch) 为负 → ID 为负 →
        // Base62Codec 抛异常，表现为"创建接口偶发 500"，与实际原因相距甚远。必须启动即拒绝。
        ShortLinkProperties properties = properties(1L, 5L);
        properties.getSnowflake().setEpochMillis(System.currentTimeMillis() + 60_000L);

        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(properties));
    }

    @Test
    void generatedIdStillFitsIntoShortCodeColumn() {
        // 实战校验：雪花 ID 明显大于自增号，Base62 后的长度必须仍能放进 code VARCHAR(16)
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(properties(1023L, 5L));

        long id = generator.nextId();
        String code = Base62Codec.encode(id);

        assertTrue(code.length() <= 16, "短码长度 " + code.length() + " 超出 t_link.code 字段容量");
        assertTrue(code.length() >= 8, "雪花 ID 的短码本来就比号段长，这是它的固有代价");
    }
}
