package com.shortlink.service;

import com.shortlink.dto.StatsRespDTO;

import java.time.LocalDate;

/**
 * 短链访问统计服务。
 *
 * V1：同步写 Redis（跳转链路内）
 * V2：由 MQ 消费端调用本服务写 Redis，跳转链路只投递消息
 * V3+：本服务仍是「实时口径」的入口，MySQL 只存定时快照
 */
public interface StatsService {

    /**
     * 记录一次成功跳转（PV / UV / 独立 IP / 来源 / 设备）。
     * 实现内部吞掉异常：用于跳转线程内调用，统计失败不影响跳转
     */
    void recordVisit(String code, String clientIp, String userAgent, String referer);

    /**
     * 严格版：写失败直接抛异常。
     * 供 MQ 消费端调用 —— 只有抛异常才能触发重试并最终进入死信队列；
     * 若消费端复用吞异常的版本，消息会被正常 ack，重试机制形同虚设
     *
     * @param visitDate 访问所属统计日期（取自消息时间），保证跨零点时归入访问当天
     */
    void recordVisitStrict(String code, String clientIp, String userAgent, String referer, LocalDate visitDate);

    /**
     * 查询某短码某日的统计（date 为空则取当天）
     */
    StatsRespDTO queryStats(String code, String date);
}
