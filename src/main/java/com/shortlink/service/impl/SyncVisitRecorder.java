package com.shortlink.service.impl;

import com.shortlink.service.StatsService;
import com.shortlink.service.StatsSnapshotService;
import com.shortlink.service.VisitRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 同步记录（V2 降级路径，等价 V1 行为）：MQ 关闭或不可用时使用。
 *
 * 代价：统计写入（Redis 多次往返 + UA/Referer 解析）重新回到跳转线程里，
 * 这正是压测中 S3 场景吞吐下降的原因 —— 保留它作为对照与兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "shortlink.mq", name = "enabled", havingValue = "false")
public class SyncVisitRecorder implements VisitRecorder {

    private final StatsService statsService;
    private final StatsSnapshotService statsSnapshotService;

    @Override
    public void record(String code, String clientIp, String userAgent, String referer) {
        // 与 MQ 消费端保持一致的顺序：先标记脏、再写统计（同步路径虽无重试，但语义统一避免误读）
        LocalDate today = LocalDate.now();
        statsSnapshotService.markDirty(code, today);
        statsService.recordVisit(code, clientIp, userAgent, referer);
    }
}
