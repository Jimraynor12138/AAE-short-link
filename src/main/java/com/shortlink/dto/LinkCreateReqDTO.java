package com.shortlink.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创建短链请求
 */
@Data
public class LinkCreateReqDTO {

    /** 原始长 URL */
    @NotBlank(message = "长链接不能为空")
    @Pattern(regexp = "^https?://.+", message = "长链接必须以 http:// 或 https:// 开头")
    @Size(max = 768, message = "长链接长度不能超过 768")
    private String originalUrl;

    /** 分组标识，空则归入 default 分组 */
    @Size(max = 32, message = "分组标识长度不能超过 32")
    private String gid;

    /** 有效期类型：1 永久，2 自定义 */
    @jakarta.validation.constraints.NotNull(message = "有效期类型不能为空")
    private Integer validType;

    /** 过期时间，validType=2 时必填 */
    private LocalDateTime validDate;

    /** 描述 */
    @Size(max = 512, message = "描述长度不能超过 512")
    private String description;
}
