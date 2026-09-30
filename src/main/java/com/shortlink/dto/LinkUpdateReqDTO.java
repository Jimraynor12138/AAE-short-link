package com.shortlink.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 修改短链请求（字段为空表示不修改）
 */
@Data
public class LinkUpdateReqDTO {

    @NotNull(message = "短链 ID 不能为空")
    private Long id;

    /** 原始长 URL，与创建接口保持一致的格式校验 */
    @Pattern(regexp = "^https?://.+", message = "长链接必须以 http:// 或 https:// 开头")
    @Size(max = 768, message = "长链接长度不能超过 768")
    private String originalUrl;

    /** 分组标识 */
    @Size(max = 32, message = "分组标识长度不能超过 32")
    private String gid;

    /** 启用状态：0 启用，1 停用 */
    @Min(value = 0, message = "启用状态仅支持 0-启用 1-停用")
    @Max(value = 1, message = "启用状态仅支持 0-启用 1-停用")
    private Integer enableStatus;

    /** 有效期类型：1 永久，2 自定义 */
    @Min(value = 1, message = "有效期类型仅支持 1-永久 2-自定义")
    @Max(value = 2, message = "有效期类型仅支持 1-永久 2-自定义")
    private Integer validType;

    /** 过期时间 */
    private LocalDateTime validDate;

    /** 描述 */
    @Size(max = 512, message = "描述长度不能超过 512")
    private String description;
}
