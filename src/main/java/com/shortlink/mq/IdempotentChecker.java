package com.shortlink.mq;

import com.shortlink.config.ShortLinkProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 消费幂等检查（V2）：消息可能因重试/重投被消费多次，用 Redis SETNX 做一次性标记。
 *
 * 为什么必须有：PV 是累加指标，重复消费会凭空多加点击量。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotentChecker {

    private static final String KEY_PREFIX = "short-link:mq:consumed:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ShortLinkProperties properties;

    /**
     * 尝试占用该消息 ID
     *
     * @return true 表示首次消费（可以继续处理），false 表示重复消息（应跳过）
     */
    public boolean tryConsume(String visitId) {
        if (visitId == null || visitId.isBlank()) {
            // 没有消息 ID 就无法去重：若仍用固定 key，会导致这类消息只有第一条能通过，
            // 之后全部被误判为重复而丢弃。这里选择「按首次处理」并告警。
            log.warn("统计消息缺少 visitId，跳过幂等校验（该消息无法去重）");
            return true;
        }
        try {
            Boolean first = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + visitId, "1",
                    Duration.ofSeconds(properties.getMq().getIdempotentTtlSeconds()));
            return Boolean.TRUE.equals(first);
        } catch (Exception e) {
            // Redis 不可用时宁可重复统计也不要丢数据
            log.warn("幂等校验异常，按首次消费处理: visitId={}", visitId, e);
            return true;
        }
    }

    /**
     * 释放幂等标记：处理失败需要重试时调用，否则重试会被自己挡掉
     */
    public void release(String visitId) {
        if (visitId == null || visitId.isBlank()) {
            return;
        }
        try {
            stringRedisTemplate.delete(KEY_PREFIX + visitId);
        } catch (Exception e) {
            log.error("释放幂等标记失败: visitId={}", visitId, e);
        }
    }
}
