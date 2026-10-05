package com.shortlink.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 缓存异步刷新线程池（V3.2）。
 *
 * 为什么需要独立线程池：逻辑过期后要把「回源重建」挪出请求线程，否则只是换了个地方阻塞。
 *
 * 参数取舍：
 * - 线程数很小（默认 2）：刷新是低频后台任务，不追求吞吐，避免与跳转线程抢 CPU
 * - 有界队列：队列满时必须能"放弃"刷新任务，绝不能因为刷新积压把内存打爆
 * - 拒绝策略用 **AbortPolicy（抛 RejectedExecutionException）而不是 DiscardPolicy**：
 *   丢弃任务时调用方必须感知到，否则 `CacheRefreshServiceImpl` 里的"在飞标记"
 *   不会被清理 —— 该短码在本实例内将再也无法触发刷新（标记泄漏）。
 *   曾用 DiscardPolicy，导致调用方的 catch 分支变成死代码，见 V3.5 审查报告。
 */
@Configuration
public class CacheExecutorConfig {

    @Bean("cacheRefreshExecutor")
    public Executor cacheRefreshExecutor(ShortLinkProperties properties) {
        ShortLinkProperties.AntiBreakdown config = properties.getCache().getAntiBreakdown();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(config.getRefreshPoolSize());
        executor.setMaxPoolSize(config.getRefreshPoolSize());
        executor.setQueueCapacity(config.getRefreshQueueCapacity());
        executor.setThreadNamePrefix("cache-refresh-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
