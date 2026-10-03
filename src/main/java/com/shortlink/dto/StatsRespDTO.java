package com.shortlink.dto;

import lombok.Data;

import java.util.Map;

/**
 * 短链访问统计响应（V1：数据源为 Redis）
 */
@Data
public class StatsRespDTO {

    /** 短码 */
    private String code;

    /** 统计日期 yyyy-MM-dd */
    private String date;

    /** 点击量 PV */
    private long pv;

    /** 独立访客 UV（HyperLogLog 基数估算） */
    private long uv;

    /** 独立 IP 数（HyperLogLog 基数估算） */
    private long ipCnt;

    /** 来源分布：来源域名 -> 次数（V2 新增，direct 表示直接访问） */
    private Map<String, Long> refererStats;

    /** 设备分布：mobile/pc/tablet/bot/unknown -> 次数（V2 新增） */
    private Map<String, Long> deviceStats;
}
