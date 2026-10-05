package com.shortlink.cache;

import com.shortlink.config.ShortLinkProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 本地缓存失效广播的订阅者（V3.3）。
 *
 * 收到其他实例的失效消息后，清掉本实例对应的 L1 条目。
 * 异常必须全部吞掉：消息处理失败只能影响"缓存新鲜度"，绝不能影响服务运行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheInvalidationListener implements MessageListener {

    private final LocalLinkCache localLinkCache;
    private final ShortLinkProperties properties;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String code = new String(message.getBody(), StandardCharsets.UTF_8);
            if (code.isBlank()) {
                return;
            }
            localLinkCache.invalidate(CacheKeyBuilder.buildLinkKey(properties.getDomain(), code));
            log.debug("收到本地缓存失效广播: code={}", code);
        } catch (Exception e) {
            log.error("处理本地缓存失效广播异常（忽略，等待 L1 TTL 过期）", e);
        }
    }
}
