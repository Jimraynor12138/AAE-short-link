package com.shortlink.mq.consumer;

import com.shortlink.mq.IdempotentChecker;
import com.shortlink.mq.message.VisitMessage;
import com.shortlink.service.StatsService;
import com.shortlink.service.StatsSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 访问统计消费者（V2）。
 *
 * 消费流程：幂等校验 -> 写 Redis 实时统计（PV/UV/IP/来源/设备）-> 标记脏短码等待定时落库。
 *
 * 失败处理：抛出异常触发 Spring AMQP 重试（最多 3 次）；
 * 重试用尽后因 default-requeue-rejected=false 进入死信队列，而不是无限重投。
 * 重试前会释放幂等标记，否则第二次消费会被自己挡掉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "shortlink.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VisitStatsConsumer {

    private final IdempotentChecker idempotentChecker;
    private final StatsService statsService;
    private final StatsSnapshotService statsSnapshotService;

    @RabbitListener(queues = "${shortlink.mq.queue}")
    public void onVisit(VisitMessage message) {
        if (message == null) {
            // 例如 payload 为空导致转换结果为空：直接丢弃，不必走重试/死信（重投也无法处理）
            log.warn("收到空的统计消息，已丢弃");
            return;
        }
        String visitId = message.getVisitId();
        if (!idempotentChecker.tryConsume(visitId)) {
            // 重复消息（重投/重试）：跳过，避免 PV 被多加
            log.debug("重复的统计消息，已跳过: visitId={}", visitId);
            return;
        }
        LocalDate visitDate = resolveVisitDate(message);
        try {
            // 顺序很重要：先标记脏（内存操作、重复无害），再写 Redis 统计。
            // 若写统计失败触发重试，脏标记重复不会造成任何影响；
            // 反过来（先写统计后标记）则可能在重试时把 PV 重复累加。
            statsSnapshotService.markDirty(message.getCode(), visitDate);
            statsService.recordVisitStrict(message.getCode(), message.getClientIp(),
                    message.getUserAgent(), message.getReferer(), visitDate);
        } catch (Exception e) {
            log.error("统计消息处理失败，将按重试策略重投: visitId={}, code={}", visitId, message.getCode(), e);
            idempotentChecker.release(visitId);
            throw e;
        }
    }

    /**
     * 统计日期取消息发生时间，而不是消费时刻：跨零点时消息仍归入访问当天
     */
    private LocalDate resolveVisitDate(VisitMessage message) {
        return message.getVisitTime() == null ? LocalDate.now() : message.getVisitTime().toLocalDate();
    }
}
