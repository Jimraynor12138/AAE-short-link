package com.shortlink.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 短链系统自有配置项
 */
@Data
@Component
@ConfigurationProperties(prefix = "shortlink")
public class ShortLinkProperties {

    /**
     * 短链域名，V0 全局统一，不做多域名路由
     */
    private String domain = "localhost:8080";

    /**
     * 发号器类型：auto / redis / segment / snowflake，由各实现类上的
     * {@code @ConditionalOnProperty} 决定装配哪个实现
     */
    private String idGeneratorType = "auto";

    /**
     * 缓存配置（V1.1）
     */
    private Cache cache = new Cache();

    /**
     * 是否启用访问统计（V1.3）。关闭后跳转链路不再写 Redis 统计，
     * 用于压测对比与故障降级
     */
    private boolean statsEnabled = true;

    /**
     * MQ 异步统计配置（V2）
     */
    private Mq mq = new Mq();

    /**
     * 统计聚合落库配置（V2）
     */
    private StatsPersist statsPersist = new StatsPersist();

    @Data
    public static class Mq {

        /**
         * false 时退化为 V1 的同步统计（降级路径）。
         * 场景：MQ 不可用、或压测做对照实验
         */
        private boolean enabled = true;

        private String exchange = "short-link.visit.exchange";

        private String queue = "short-link.visit.queue";

        private String routingKey = "short-link.visit";

        private String deadLetterExchange = "short-link.visit.dlx";

        private String deadLetterQueue = "short-link.visit.dlq";

        /**
         * 消费幂等 key 保留时间（秒）= 允许的消息重投窗口。
         *
         * 容量约束：key 数量 ≈ 峰值 QPS × TTL。
         * 例如 3000 QPS × 60s ≈ 18 万个 key（约 20MB），而 600s 会放大到 180 万个（约 200MB）。
         * 因此该值应贴近「实际重投间隔」而不是取大值。
         */
        private long idempotentTtlSeconds = 60;
    }

    @Data
    public static class StatsPersist {

        /**
         * 统计快照落库间隔（毫秒）。
         * 注意：@Scheduled 的 fixedDelayString 只接受毫秒数字符串，
         * 写成 "2s" 会启动失败（Invalid fixedDelayString value），
         * 也不要用 "2"，那会被当成 2 毫秒导致疯狂写库
         */
        private long flushIntervalMs = 2000;

        /** 单次 flush 最大条目数，防止一次写库过多 */
        private int maxBatchSize = 1000;

        /**
         * 是否启用「定时兜底扫描」（启动时的那一次始终执行）。
         *
         * 用布尔开关而不是间隔数值：@Scheduled 的 fixedDelay 若被配成 0，
         * 会变成 0 延迟高频空转并占满单线程调度器（连带拖慢 flush），
         * 所以间隔在代码里固定为常量，只暴露开关。
         */
        private boolean rescanEnabled = true;
    }

    @Data
    public static class Cache {

        /**
         * 缓存总开关。关闭后跳转直接查 DB（等价 V0 行为），
         * 用于压测基线对比与缓存故障时的降级
         */
        private boolean enabled = true;

        /** 正常短链缓存基础 TTL（秒） */
        private long ttlSeconds = 1800;

        /** TTL 随机抖动上限（秒）：实际 TTL = ttlSeconds + [0, jitterSeconds] */
        private long jitterSeconds = 600;

        /** 空值缓存 TTL（秒），防穿透 */
        private long nullTtlSeconds = 60;

        /** 统计 key TTL（秒），默认 7 天：V1 统计只存 Redis，需保留一段可查询窗口 */
        private long statsTtlSeconds = 604800;
    }
}
