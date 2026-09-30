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

    @Data
    public static class Cache {

        /** 正常短链缓存基础 TTL（秒） */
        private long ttlSeconds = 1800;

        /** TTL 随机抖动上限（秒）：实际 TTL = ttlSeconds + [0, jitterSeconds] */
        private long jitterSeconds = 600;

        /** 空值缓存 TTL（秒），防穿透 */
        private long nullTtlSeconds = 60;
    }
}
