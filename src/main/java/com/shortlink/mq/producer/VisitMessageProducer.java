package com.shortlink.mq.producer;

import com.shortlink.config.ShortLinkProperties;
import com.shortlink.mq.message.VisitMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 访问统计消息生产者（V2）。
 *
 * 可靠性取舍：统计不是钱，允许偶发丢一条 —— 投递失败只记 error 日志，绝不影响跳转。
 * 若要求不丢，需要 publisher confirm + 本地消息表，代价是跳转 RT 变长，本项目不采用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "shortlink.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VisitMessageProducer {

    private final RabbitTemplate rabbitTemplate;
    private final ShortLinkProperties properties;

    public VisitMessage buildMessage(String code, String clientIp, String userAgent, String referer) {
        VisitMessage message = new VisitMessage();
        message.setVisitId(UUID.randomUUID().toString());
        message.setCode(code);
        message.setClientIp(clientIp);
        message.setUserAgent(userAgent);
        message.setReferer(referer);
        message.setVisitTime(LocalDateTime.now());
        return message;
    }

    /**
     * 发送消息：吞掉所有异常，保证跳转链路不受 MQ 可用性影响
     */
    public void send(VisitMessage message) {
        try {
            rabbitTemplate.convertAndSend(properties.getMq().getExchange(),
                    properties.getMq().getRoutingKey(), message);
        } catch (Exception e) {
            log.error("统计消息投递失败，已忽略（不影响跳转）: visitId={}, code={}",
                    message.getVisitId(), message.getCode(), e);
        }
    }
}
