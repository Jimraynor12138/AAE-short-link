package com.shortlink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 短链统计聚合实体（V2），对应表 t_link_stats。
 * 每行 = 一条短链 + 一天的快照，写入量从「每次点击」降为「每条链每天一行」。
 */
@Data
@TableName("t_link_stats")
public class LinkStatsDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long linkId;

    private String code;

    /** 统计日期 */
    private LocalDate date;

    /** 点击量（来自 Redis 实时口径的快照） */
    private Long pv;

    /** 独立访客数（HyperLogLog 估算值快照） */
    private Long uv;

    /** 独立 IP 数（HyperLogLog 估算值快照） */
    private Long ipCnt;

    /** 直接访问次数（无 Referer） */
    private Long defaultCnt;

    /** 来源分布 JSON */
    private String refererStats;

    /** 设备分布 JSON */
    private String deviceStats;

    /** 地域分布 JSON（后续版本填充） */
    private String localeStats;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
