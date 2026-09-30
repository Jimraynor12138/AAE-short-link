package com.shortlink.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 短链响应 DTO
 */
@Data
public class LinkRespDTO {

    private Long id;

    /** 短码 */
    private String code;

    /** 完整短链地址，如 http://localhost:8080/aB3xK9 */
    private String fullShortUrl;

    /** 原始长 URL */
    private String originalUrl;

    /** 分组标识 */
    private String gid;

    /** 启用状态：0 启用，1 停用 */
    private Integer enableStatus;

    /** 有效期类型：1 永久，2 自定义 */
    private Integer validType;

    /** 过期时间 */
    private LocalDateTime validDate;

    /** 描述 */
    private String description;

    /** 创建时间 */
    private LocalDateTime createTime;
}
