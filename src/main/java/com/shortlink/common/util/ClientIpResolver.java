package com.shortlink.common.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端真实 IP 解析（V3.3 从 RedirectController 抽出，供跳转与限流共用）。
 *
 * 优先取 X-Forwarded-For 的第一个地址（前置 Nginx/网关时才有值，
 * 注意：该头可被伪造，生产环境应只信任来自可信代理的转发头，或在网关统一覆盖）。
 */
public final class ClientIpResolver {

    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader(HEADER_X_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
