package com.shortlink.cache;

import com.shortlink.config.ShortLinkProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 降级闸门测试（V3.3 防雪崩）：正常态放行、降级态限流、许可归还、状态粘滞
 */
class DegradationGuardTest {

    private ShortLinkProperties properties;
    private DegradationGuard guard;

    @BeforeEach
    void setUp() {
        properties = new ShortLinkProperties();
        properties.getCache().getDegradation().setMaxConcurrentDbQueries(1);
        properties.getCache().getDegradation().setAcquireTimeoutMillis(10);
        properties.getCache().getDegradation().setDegradeWindowSeconds(5);
        guard = new DegradationGuard(properties);
    }

    @Test
    void normalStateAlwaysAllowsWithoutConsumingPermit() {
        assertFalse(guard.isDegraded());

        DegradationGuard.Permit permit = guard.tryAcquirePermit();

        assertNotNull(permit);
        // 正常态不占信号量：许可数不变
        assertEquals(1, guard.availablePermits());
    }

    @Test
    void degradedStateRejectsWhenPermitsExhausted() {
        guard.markDegraded("test");
        assertTrue(guard.isDegraded());

        DegradationGuard.Permit first = guard.tryAcquirePermit();
        assertNotNull(first);
        // 第二个请求拿不到许可 → 被拒绝（快速失败，保护 DB）
        assertNull(guard.tryAcquirePermit());
        assertEquals(1, guard.rejectedCount());
    }

    @Test
    void closingPermitRestoresCapacity() {
        guard.markDegraded("test");
        DegradationGuard.Permit permit = guard.tryAcquirePermit();
        assertNotNull(permit);

        permit.close();

        assertEquals(1, guard.availablePermits());
        assertNotNull(guard.tryAcquirePermit());
    }

    @Test
    void permitCloseIsIdempotentForNoop() {
        // 正常态返回空许可：重复 close 不应影响信号量
        DegradationGuard.Permit noop = guard.tryAcquirePermit();
        noop.close();
        noop.close();

        assertEquals(1, guard.availablePermits());
    }

    @Test
    void markHealthyExitsDegradedState() {
        guard.markDegraded("test");

        guard.markHealthy();

        assertFalse(guard.isDegraded());
    }

    @Test
    void disabledDegradationNeverLimits() {
        properties.getCache().getDegradation().setEnabled(false);
        guard.markDegraded("test");

        // 开关关闭时连降级态都不记录
        assertFalse(guard.isDegraded());
        assertNotNull(guard.tryAcquirePermit());
    }
}
