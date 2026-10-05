package com.shortlink.idgenerator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis INCR 发号器测试：正常发号、异常分支，以及「号源落后于 DB」的自愈
 */
@ExtendWith(MockitoExtension.class)
class RedisIncrIdGeneratorTest {

    private static final String KEY = RedisIncrIdGenerator.ID_KEY;

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private RedisIncrIdGenerator idGenerator;

    @BeforeEach
    void setUp() {
        idGenerator = new RedisIncrIdGenerator(stringRedisTemplate, jdbcTemplate);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
    }

    @Test
    void nextIdReturnsIncrValue() {
        when(valueOperations.increment(KEY)).thenReturn(101L);
        assertEquals(101L, idGenerator.nextId());
    }

    @Test
    void nextIdThrowsWhenRedisReturnsNull() {
        when(valueOperations.increment(KEY)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> idGenerator.nextId());
    }

    @Test
    void seedSowsCounterWhenKeyAbsent() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(42L);
        when(valueOperations.get(KEY)).thenReturn(null);

        idGenerator.seedIfAbsent();

        verify(valueOperations).increment(KEY, 42L);
    }

    @Test
    void seedRaisesCounterWhenItLagsBehindDb() {
        // Redis 从旧快照恢复：计数 19，DB 已用到 22 —— 不抬升就会连续撞号
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(22L);
        when(valueOperations.get(KEY)).thenReturn("19");

        idGenerator.seedIfAbsent();

        verify(valueOperations).increment(KEY, 3L);
    }

    @Test
    void seedLeavesCounterAloneWhenAlreadyAhead() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(22L);
        when(valueOperations.get(KEY)).thenReturn("100");

        idGenerator.seedIfAbsent();

        verify(valueOperations, never()).increment(anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void recoverAfterConflictRaisesCounter() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(30L);
        when(valueOperations.get(KEY)).thenReturn("21");

        idGenerator.recoverAfterConflict();

        verify(valueOperations).increment(KEY, 9L);
    }

    @Test
    void corruptedCounterIsTreatedAsZeroAndRaised() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(5L);
        when(valueOperations.get(KEY)).thenReturn("not-a-number");

        idGenerator.seedIfAbsent();

        verify(valueOperations).increment(KEY, 5L);
    }

    @Test
    void nextIdReseedsWhenCounterWasLost() {
        // 计数回到 1（key 曾被清空），DB 最大 id 为 500：跳过安全区间
        when(valueOperations.increment(KEY)).thenReturn(1L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(500L);
        when(valueOperations.increment(KEY, 500L)).thenReturn(501L);

        assertEquals(501L, idGenerator.nextId());
    }

    @Test
    void nextIdKeepsFirstIdWhenDbEmpty() {
        when(valueOperations.increment(KEY)).thenReturn(1L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

        assertEquals(1L, idGenerator.nextId());
    }
}
