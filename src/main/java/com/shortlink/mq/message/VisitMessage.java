package com.shortlink.mq.message;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 访问统计消息（V2）。消息只放原始事实（IP/UA/Referer），解析放在消费端。
 */
@Data
public class VisitMessage implements Serializable {

    /** 消息唯一 ID，消费端幂等去重依据 */
    private String visitId;

    private String code;

    private String clientIp;

    private String userAgent;

    private String referer;

    private LocalDateTime visitTime;
}
