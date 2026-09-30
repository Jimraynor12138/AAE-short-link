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
}
