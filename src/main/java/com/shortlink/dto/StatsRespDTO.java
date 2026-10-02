package com.shortlink.dto;

import lombok.Data;

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
}
