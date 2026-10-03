package com.shortlink.service.impl;

import com.shortlink.mq.producer.VisitMessageProducer;
import com.shortlink.service.VisitRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * MQ 异步记录（V2 正常路径）：跳转链路只投递消息，UA/Referer 解析与统计写入都在消费端完成。
 *
 * 收益：跳转 RT 不再包含统计开销（V1 压测显示同步统计让 QPS 掉了 30%+）
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "shortlink.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AsyncVisitRecorder implements VisitRecorder {

    private final VisitMessageProducer visitMessageProducer;

    @Override
    public void record(String code, String clientIp, String userAgent, String referer) {
        visitMessageProducer.send(visitMessageProducer.buildMessage(code, clientIp, userAgent, referer));
    }
}
