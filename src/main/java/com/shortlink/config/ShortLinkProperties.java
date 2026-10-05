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

    /**
     * 布隆过滤器配置（V3.1：防缓存穿透）
     */
    private Bloom bloom = new Bloom();

    /**
     * 号段模式发号配置（V3.5）
     */
    private Segment segment = new Segment();

    /**
     * 雪花算法发号配置（V3.5）
     */
    private Snowflake snowflake = new Snowflake();

    @Data
    public static class Bloom {

        /**
         * 总开关。关闭后跳转链路不做布隆拦截（退化为 V2 的空值缓存方案）
         */
        private boolean enabled = true;

        /** 预计短码总量：决定位图大小，估小了误判率会上升 */
        private long capacity = 1_000_000L;

        /** 目标误判率：越小位图越大（0.01 ≈ 每 100 个不存在的短码约 1 个被放行） */
        private double falsePositiveRate = 0.01d;

        /** 位图 key（Redis BitMap） */
        private String key = "short-link:bloom:codes";

        /** 预热完成标记 key：跨进程/重启判断位图是否已与 DB 对齐 */
        private String initFlagKey = "short-link:bloom:init";

        /** 预热时每批从 DB 读取的短码数 */
        private int warmupBatchSize = 1000;

        /** 本地判定「不存在」时是否回查 Redis 确认（V3.4，见 ShortLinkBloomFilter 注释） */
        private boolean verifyOnLocalMiss = true;
    }

    /**
     * 号段模式配置（V3.5）。
     *
     * 一次向 `t_segment` 申请 step 个号放在内存里发，用完再申请下一段。
     * 「双 buffer」= 当前段剩余不足 prefetch-remaining-percent% 时**异步预取**下一段，
     * 让段切换不需要等待网络 —— 这是号段模式（Leaf-segment）相对 Redis INCR 的关键优化。
     */
    @Data
    public static class Segment {

        /** 业务标识：t_segment 的主键（一个业务一行） */
        private String bizTag = "short-link";

        /**
         * 号段长度。取舍：越大 → 发号的 DB 压力越小、预取越不频繁；
         * 但进程重启时**未用完的号会浪费**（号只要求唯一，不要求连续，功能上无害）
         */
        private int step = 1000;

        /** 当前段剩余比例低于该百分比时触发异步预取（双 buffer 的触发线） */
        private int prefetchRemainingPercent = 10;

        /** 同步分配号段失败时的重试次数 */
        private int allocRetryTimes = 3;

        /** 号段分配重试间隔（毫秒） */
        private long allocRetryIntervalMillis = 50;
    }

    /**
     * 雪花算法配置（V3.5）。
     *
     * 64 位 = 1 符号位 + 41 位时间戳(ms) + 10 位机器 ID + 12 位序列号。
     * 完全本地生成、零外部依赖（性能天花板最高），代价是：
     * 1. ID 远大于自增/号段（Base62 后约 10~11 位短码，而号段只有 1~3 位）
     * 2. 必须处理**时钟回拨**与**机器 ID 分配**
     */
    @Data
    public static class Snowflake {

        /**
         * 机器 ID（0~1023）。多实例部署必须互不相同，否则会重号。
         * 生产环境一般由配置中心 / 环境变量 / K8s 序号注入，本项目用配置项模拟
         */
        private long workerId = 0L;

        /**
         * 自定义纪元（毫秒）：用「项目上线时间」而不是 1970，可显著缩小 ID 数值。
         * 默认 2024-01-01T00:00:00Z；41 位时间戳从纪元起约有 69 年可用
         */
        private long epochMillis = 1704067200000L;

        /**
         * 允许的时钟回拨上限（毫秒）：不超过 → 自旋等待追上；超过 → **拒绝发号并抛异常**。
         * 大幅回拨时宁可让创建失败并告警，也不能生成重复 ID
         */
        private long maxBackwardMillis = 5L;

        /**
         * 本地判定「不存在」时是否回查 Redis 确认（V3.4）。
         *
         * 多实例下本地位图快照可能过期（其他实例刚创建的短码），若直接信任本地结论会把正常短链
         * 误拦成 404。开启后仅对"判定不存在"的结论回查一次（pipelined，1 个 RTT），
         * 真实流量走的是"本地判定存在"的快路径，不受影响。
         * 单实例部署可关掉以省掉这次 RTT。
         */
        private boolean verifyOnLocalMiss = true;
    }

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

        /** 防击穿配置（V3.2） */
        private AntiBreakdown antiBreakdown = new AntiBreakdown();

        /** 本地一级缓存配置（V3.3） */
        private Local local = new Local();

        /** 降级保护配置（V3.3 防雪崩） */
        private Degradation degradation = new Degradation();
    }

    /**
     * 本地一级缓存配置（V3.3，Caffeine）。
     *
     * 定位：Redis（L2）是"共享主缓存"，本地缓存（L1）只解决**热点 key 反复打同一个 Redis 分片**的问题。
     * 代价是数据分散在各实例堆内存里，失效需要广播 —— 因此 L1 的 TTL 必须远小于 L2，
     * 让"广播丢失/实例网络抖动"导致的不一致最多只存活一个 L1 TTL。
     */
    @Data
    public static class Local {

        /** 是否启用本地缓存 */
        private boolean enabled = true;

        /** 最大条目数（按实例）：每个实例堆内存中最多缓存多少短码 */
        private long maxSize = 10000;

        /** 本地缓存 TTL（秒）：远小于 Redis TTL，作为多实例失效消息丢失的兜底 */
        private long ttlSeconds = 30;

        /** 是否开启 Caffeine 命中率统计 */
        private boolean recordStats = true;
    }

    /**
     * 写接口限流配置（V3.3 防雪崩）
     */
    private RateLimit rateLimit = new RateLimit();

    /**
     * 降级保护配置（V3.3 防雪崩）。
     *
     * 场景：Redis 整体故障时，全部跳转流量会直接砸向 MySQL —— 缓存雪崩的最终形态。
     * 对策：探测到 Redis 异常后进入"降级态"，对回源 DB 的并发加闸门，
     * 宁可让少量请求快速失败（503），也不能让 DB 被拖垮（DB 挂了才是真正的全站故障）。
     */
    @Data
    public static class Degradation {

        /** 是否启用降级保护 */
        private boolean enabled = true;

        /** 降级态下允许的最大并发回源数 */
        private int maxConcurrentDbQueries = 20;

        /** 获取回源许可的等待时间（毫秒）：拿不到就快速失败，避免线程堆积 */
        private long acquireTimeoutMillis = 50;

        /** 降级态保持窗口（秒）：最后一次 Redis 异常后多久内仍视为降级 */
        private long degradeWindowSeconds = 10;
    }

    /**
     * 接口限流配置（V3.3 防雪崩）。
     *
     * 只对**写接口**（创建/修改/删除）限流：跳转链路是核心读服务，用降级闸门保护 DB 更合适；
     * 写接口一旦被刷，直接消耗发号器、唯一索引与 DB 写入，是更值得限流的入口。
     */
    @Data
    public static class RateLimit {

        /** 是否启用写接口限流 */
        private boolean enabled = true;

        /** 滑动窗口大小（秒） */
        private long windowSeconds = 60;

        /** 单窗口内单客户端允许的写请求数 */
        private int writeLimitPerWindow = 120;
    }

    /**
     * 缓存击穿防护配置（V3.2）：
     * 热点短码缓存失效的瞬间，大量并发请求会同时回源 DB（击穿）。
     * 两手准备：逻辑过期（不阻塞请求）+ 互斥重建（未命中时只放一个请求回源）。
     */
    @Data
    public static class AntiBreakdown {

        /** 是否启用「逻辑过期 + 异步刷新」 */
        private boolean logicalExpireEnabled = true;

        /**
         * 逻辑过期时长（秒）：写入缓存后经过该时长即视为逻辑过期并触发异步刷新。
         * 应显著小于物理 TTL，这样逻辑过期后仍有较长时间存在旧值可返回（刷新窗口）
         */
        private long logicalExpireSeconds = 300;

        /** 是否启用「互斥重建」：缓存未命中时只允许一个请求回源构建 */
        private boolean mutexRebuildEnabled = true;

        /** 互斥重建时，未抢到锁的请求最多等待多少毫秒（等待 leader 回填缓存） */
        private long mutexWaitMillis = 200;

        /** 等待轮询步长（毫秒） */
        private long mutexWaitStepMillis = 20;

        /** 异步刷新线程池大小 */
        private int refreshPoolSize = 2;

        /** 异步刷新队列容量：满则丢弃刷新任务（请求已拿到旧值，刷新是尽力而为） */
        private int refreshQueueCapacity = 1000;
    }
}
