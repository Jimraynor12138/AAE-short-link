package com.shortlink.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 短链分页查询请求
 */
@Data
public class LinkPageReqDTO {

    /** 当前页，从 1 开始 */
    @Min(value = 1, message = "当前页最小为 1")
    private long current = 1;

    /** 每页条数 */
    @Min(value = 1, message = "每页条数最小为 1")
    @Max(value = 100, message = "每页条数最大为 100")
    private long size = 10;

    /** 分组标识，为空查全部分组 */
    private String gid;
}
