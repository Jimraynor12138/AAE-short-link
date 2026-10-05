package com.shortlink.idgenerator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 号段分配器测试（V3.5）：原子分配两步、缺行自建、读不到值时的失败语义、只增不减的起点抬升
 *
 * 注意：JdbcTemplate.update 是 varargs 方法，打桩要用 any(Object[].class) 匹配整组可变参数，
 * 否则参数个数不同的调用会触发 Mockito 的 PotentialStubbingProblem
 */
@ExtendWith(MockitoExtension.class)
class SegmentAllocatorTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SegmentAllocator allocator;

    @BeforeEach
    void setUp() {
        allocator = new SegmentAllocator(jdbcTemplate);
        // 默认：号段行已存在（COUNT=1）；个别用例覆盖为 0 模拟"行不存在"
        org.mockito.Mockito.lenient()
                .when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);
        org.mockito.Mockito.lenient()
                .when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
    }

    @Test
    void allocateReturnsNewMaxId() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2000L);

        assertEquals(2000L, allocator.allocate("short-link", 1000));

        // 关键：先 UPDATE 再 SELECT（同一事务内 SELECT 才能读到刚分配的上界）
        verify(jdbcTemplate).update("UPDATE t_segment SET max_id = max_id + ? WHERE biz_tag = ?",
                1000, "short-link");
        verify(jdbcTemplate).queryForObject("SELECT max_id FROM t_segment WHERE biz_tag = ?",
                Long.class, "short-link");
    }

    @Test
    void allocateCreatesRowWhenMissing() {
        // 探测到行不存在（COUNT=0）→ 触发 INSERT 自建
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1000L);

        assertEquals(1000L, allocator.allocate("short-link", 1000));

        verify(jdbcTemplate).update("INSERT IGNORE INTO t_segment(biz_tag, max_id, step) VALUES (?, 0, ?)",
                "short-link", 1000);
    }

    @Test
    void allocateSkipsInsertWhenRowExists() {
        // 回归：V3.5 审查发现旧实现用"空 UPDATE"探测，行存在也返回 0 → 每次分配都白跑一次 INSERT
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1000L);

        allocator.allocate("short-link", 1000);

        verify(jdbcTemplate, org.mockito.Mockito.never())
                .update(org.mockito.ArgumentMatchers.contains("INSERT IGNORE"), any(Object[].class));
    }

    @Test
    void allocateFailsWhenMaxIdMissing() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> allocator.allocate("short-link", 1000));
    }

    @Test
    void raiseToUsesGreatestSoCounterNeverGoesBackwards() {
        allocator.raiseTo("short-link", 1000, 5000L);

        verify(jdbcTemplate).update("UPDATE t_segment SET max_id = GREATEST(max_id, ?) WHERE biz_tag = ?",
                5000L, "short-link");
    }
}
