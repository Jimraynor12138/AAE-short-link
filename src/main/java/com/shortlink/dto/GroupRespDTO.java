package com.shortlink.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分组响应 DTO
 */
@Data
public class GroupRespDTO {

    private Long id;

    /** 分组标识 */
    private String gid;

    /** 分组名称 */
    private String name;

    /** 排序值 */
    private Integer sortOrder;

    private LocalDateTime createTime;
}
