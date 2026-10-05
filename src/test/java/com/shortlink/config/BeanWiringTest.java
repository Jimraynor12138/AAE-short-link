package com.shortlink.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortlink.bloom.BloomBitmapStore;
import com.shortlink.cache.DegradationGuard;
import com.shortlink.cache.LocalLinkCache;
import com.shortlink.common.ratelimit.RateLimitInterceptor;
import com.shortlink.common.ratelimit.SlidingWindowRateLimiter;
import com.shortlink.idgenerator.SegmentAllocator;
import com.shortlink.idgenerator.SegmentIdGenerator;
import com.shortlink.idgenerator.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Spring 装配冒烟测试（V3.3 起，V3.5 扩展到发号器与布隆过滤器）。
 *
 * 为什么需要它：单元测试都是 `new XxxService(...)` 直接构造，**测不出"Spring 能否实例化这个 Bean"**。
 * 实际踩过的坑：`SlidingWindowRateLimiter` 因有两个构造器且未标注 @Autowired，
 * Spring 去找默认构造器 → **应用启动直接失败**，而当时 163 个单测全绿。
 *
 * 这里用 ApplicationContextRunner 让 Spring 真正实例化这些组件，把该类问题挡在提交前。
 */
class BeanWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ShortLinkProperties.class)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean("segmentPrefetchExecutor", Executor.class, () -> (Executor) Runnable::run);

    // ---------------- V3.3 限流 / 降级 / 多级缓存 ----------------

    @Test
    void slidingWindowRateLimiterCanBeCreatedBySpring() {
        runner.withBean(SlidingWindowRateLimiter.class)
                .run(context -> assertThat(context).hasSingleBean(SlidingWindowRateLimiter.class));
    }

    @Test
    void rateLimitInterceptorCanBeCreatedBySpring() {
        runner.withBean(SlidingWindowRateLimiter.class)
                .withBean(RateLimitInterceptor.class)
                .run(context -> assertThat(context).hasSingleBean(RateLimitInterceptor.class));
    }

    @Test
    void localLinkCacheCanBeCreatedBySpring() {
        runner.withBean(LocalLinkCache.class)
                .run(context -> assertThat(context).hasSingleBean(LocalLinkCache.class));
    }

    @Test
    void degradationGuardCanBeCreatedBySpring() {
        runner.withBean(DegradationGuard.class)
                .run(context -> assertThat(context).hasSingleBean(DegradationGuard.class));
    }

    // ---------------- V3.4 布隆过滤器 / V3.5 发号器 ----------------

    @Test
    void bloomBitmapStoreCanBeCreatedBySpring() {
        runner.withBean(BloomBitmapStore.class)
                .run(context -> assertThat(context).hasSingleBean(BloomBitmapStore.class));
    }

    @Test
    void snowflakeIdGeneratorCanBeCreatedBySpring() {
        // 两个发号器实现都带 @ConditionalOnProperty，注册 Bean 时条件也会被求值 → 必须给出开关值
        runner.withPropertyValues("shortlink.id-generator-type=snowflake")
                .withBean(SnowflakeIdGenerator.class)
                .run(context -> assertThat(context).hasSingleBean(SnowflakeIdGenerator.class));
    }

    @Test
    void segmentIdGeneratorCanBeCreatedBySpring() {
        runner.withPropertyValues("shortlink.id-generator-type=segment")
                .withBean(SegmentAllocator.class, () -> mock(SegmentAllocator.class))
                .withBean(SegmentIdGenerator.class)
                .run(context -> assertThat(context).hasSingleBean(SegmentIdGenerator.class));
    }

    /**
     * 回归：缓存刷新线程池必须是 AbortPolicy。
     * 曾用 DiscardPolicy → 队列满时静默丢弃，调用方（CacheRefreshServiceImpl）的
     * catch 分支成为死代码、"在飞标记"泄漏。这个断言把策略选择钉住。
     */
    @Test
    void cacheRefreshExecutorUsesAbortPolicy() {
        runner.withUserConfiguration(CacheExecutorConfig.class)
                .run(context -> {
                    ThreadPoolTaskExecutor executor =
                            context.getBean("cacheRefreshExecutor", ThreadPoolTaskExecutor.class);
                    assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                            .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
                });
    }
}
