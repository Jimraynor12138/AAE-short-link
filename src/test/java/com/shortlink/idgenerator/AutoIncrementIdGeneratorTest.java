package com.shortlink.idgenerator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DB 自增发号器测试：与 t_link 进度对齐（切换发号器后不撞已有 id）
 */
@ExtendWith(MockitoExtension.class)
class AutoIncrementIdGeneratorTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AutoIncrementIdGenerator idGenerator;

    @BeforeEach
    void setUp() {
        idGenerator = new AutoIncrementIdGenerator(jdbcTemplate);
    }

    @Test
    void alignsAutoIncrementToLinkMaxId() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(22L);

        idGenerator.alignSequenceWithLinks();

        verify(jdbcTemplate).execute("ALTER TABLE t_sequence AUTO_INCREMENT = 23");
    }

    @Test
    void skipsAlignmentWhenNoLinksExist() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

        idGenerator.alignSequenceWithLinks();

        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void recoverAfterConflictRealigns() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(103L);

        idGenerator.recoverAfterConflict();

        verify(jdbcTemplate).execute("ALTER TABLE t_sequence AUTO_INCREMENT = 104");
    }
}
