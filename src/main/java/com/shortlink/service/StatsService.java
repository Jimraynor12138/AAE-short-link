package com.shortlink.service;

import com.shortlink.dto.StatsRespDTO;

/**
 * 短链访问统计服务（V1.3）。
 *
 * V1 为同步统计（跳转链路内直接写 Redis）；
 * V2 将改为跳转发消息、消费端聚合，把统计开销从跳转 RT 中剥离。
 */
public interface StatsService {

    /**
     * 记录一次成功跳转（PV/UV/IP）。实现内部保证不抛异常，统计失败不影响跳转
     */
    void recordVisit(String code, String clientIp, String userAgent);

    /**
     * 查询某短码某日的统计（date 为空则取当天）
     */
    StatsRespDTO queryStats(String code, String date);
}
