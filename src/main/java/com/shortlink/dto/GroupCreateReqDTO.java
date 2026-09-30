package com.shortlink.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建分组请求
 */
@Data
public class GroupCreateReqDTO {

    @NotBlank(message = "分组名称不能为空")
    @Size(max = 64, message = "分组名称长度不能超过 64")
    private String name;

    /** 排序值，越小越靠前 */
    private Integer sortOrder;
}
