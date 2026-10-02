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
import static org.mockito.Mockito.when;

/**
 * Redis INCR 发号器单元测试（V1.2）：正常发号、启动播种、丢号自愈、异常分支
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
    void seedIfAbsentUsesDbMaxId() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(42L);
        when(valueOperations.setIfAbsent(KEY, "42")).thenReturn(true);
        idGenerator.seedIfAbsent();
    }

    @Test
    void nextIdReseedsWhenCounterWasLost() {
        when(valueOperations.increment(KEY)).thenReturn(1L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(500L);
        when(valueOperations.increment(KEY, 500L)).thenReturn(501L);
        assertEquals(501L, idGenerator.nextId());
    }

    @Test
    void nextIdKeepsFirstIdWhenDbEmpty() {
        when(valueOperations.increment(KEY)).thenReturn(1L);
        assertEquals(1L, idGenerator.nextId());
    }
}
