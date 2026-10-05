package com.shortlink.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 短链跳转缓存值对象（V1.1）。
 *
 * 只缓存跳转校验所需的最小字段快照（约 200 字节，无大 key 风险）。
 *
 * 关键设计：缓存的是「数据快照」而非「是否可跳转的结论」，
 * enableStatus/validType/validDate 原样缓存，每次读取后在应用侧重新校验，
 * 否则「缓存时未过期、N 分钟后才过期」的短链会继续被放行。
 */
@Data
public class LinkCacheDTO {

    /** 原始长 URL */
    private String originalUrl;

    /** 启用状态：0 启用，1 停用 */
    private Integer enableStatus;

    /** 有效期类型：1 永久，2 自定义 */
    private Integer validType;

    /** 过期时间 */
    private LocalDateTime validDate;

    /**
     * 逻辑过期时间（epoch 毫秒，V3.2 防击穿用）。
     *
     * 与物理 TTL 的区别：物理 TTL 到期 key 就消失（下一个请求必然回源，热点码会击穿 DB）；
     * 逻辑过期到期后 key 仍在，请求可以先拿到「稍旧但依然可用」的值立即返回，
     * 由后台异步重建 —— 跳转场景下 URL 基本不变，可接受短暂陈旧，换取请求线程零阻塞。
     *
     * 为兼容历史缓存值（该字段不存在），读取侧按 null 处理（视为未逻辑过期）。
     */
    private Long logicalExpireAt;
}
