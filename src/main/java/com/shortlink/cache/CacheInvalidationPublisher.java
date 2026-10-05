package com.shortlink.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 本地缓存失效广播（V3.3）。
 *
 * 多实例部署时，短链在 A 实例被修改/删除，B/C 实例的本地缓存仍是旧值 —— 这是 L1 缓存绕不开的问题。
 * 用 Redis 发布订阅广播"该短码失效"，各实例收到后清掉自己的 L1。
 *
 * 可靠性说明（重点）：
 * - Pub/Sub 是**尽力而为**语义：订阅方掉线期间的消息会丢，Redis 故障时也发不出去
 * - 因此它只用来"降低不一致窗口"，最终一致性仍由 **L1 短 TTL** 兜底
 * - 广播失败绝不阻断主流程（DB 已是事实源）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheInvalidationPublisher {

    /** 失效广播频道：消息体为短码 */
    public static final String CHANNEL = "short-link:cache:invalidate";

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 广播某短码的本地缓存失效（发送方自己也要清，见 LinkServiceImpl）
     */
    public void publish(String code) {
        try {
            stringRedisTemplate.convertAndSend(CHANNEL, code);
        } catch (Exception e) {
            // 广播失败不影响正确性：其他实例的 L1 会在 ttl-seconds 内自然过期
            log.error("本地缓存失效广播失败（将由 L1 TTL 兜底）, code={}", code, e);
        }
    }
}
