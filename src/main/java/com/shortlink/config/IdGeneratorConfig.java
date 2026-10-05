package com.shortlink.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 发号器相关 Bean（V3.5）。
 *
 * 号段模式需要「异步预取下一段」的执行器（双 buffer）：
 * - 单线程足够：预取是低频（每 step 个号一次）且串行更安全
 * - 有界队列 + AbortPolicy：被拒绝时由调用方捕获并重置预取标记（下次取号再试），
 *   这里刻意不用 DiscardPolicy，否则调用方感知不到"预取没做"
 */
@Configuration
public class IdGeneratorConfig {

    @Bean("segmentPrefetchExecutor")
    public Executor segmentPrefetchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("segment-prefetch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
