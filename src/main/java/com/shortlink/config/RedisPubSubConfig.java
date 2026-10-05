package com.shortlink.config;

import com.shortlink.cache.CacheInvalidationListener;
import com.shortlink.cache.CacheInvalidationPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis 发布订阅配置（V3.3）：用于本地缓存失效广播。
 */
@Configuration
public class RedisPubSubConfig {

    /**
     * 监听容器：随 Spring 生命周期启停（容器负责连接的建立与自动重连）
     */
    @Bean
    public RedisMessageListenerContainer cacheInvalidationListenerContainer(
            RedisConnectionFactory connectionFactory,
            CacheInvalidationListener cacheInvalidationListener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(cacheInvalidationListener,
                new ChannelTopic(CacheInvalidationPublisher.CHANNEL));
        return container;
    }
}
