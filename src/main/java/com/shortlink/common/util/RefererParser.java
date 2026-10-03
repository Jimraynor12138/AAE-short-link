package com.shortlink.common.util;

import java.net.URI;

/**
 * 来源（Referer）解析（V2）：从 Referer URL 中提取来源域名，用于统计「谁把我带过来的」。
 */
public final class RefererParser {

    /** 无 Referer（直接访问/收藏夹/App 内打开） */
    public static final String DIRECT = "direct";

    private RefererParser() {
    }

    public static String parseHost(String referer) {
        if (referer == null || referer.isBlank()) {
            return DIRECT;
        }
        try {
            String host = URI.create(referer).getHost();
            return (host == null || host.isBlank()) ? DIRECT : host;
        } catch (IllegalArgumentException e) {
            // 非法 Referer 不影响统计主流程，统一归为直接访问
            return DIRECT;
        }
    }
}
