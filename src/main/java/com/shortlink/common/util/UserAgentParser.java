package com.shortlink.common.util;

/**
 * 极简 User-Agent 解析（V2）。
 *
 * 说明：生产环境通常用专门的 UA 解析库（如 yauaa、ua-parser），
 * 本类只做关键词判断，用于演示「把解析动作从跳转链路挪到消费端」这一优化点。
 */
public final class UserAgentParser {

    public static final String DEVICE_MOBILE = "mobile";
    public static final String DEVICE_TABLET = "tablet";
    public static final String DEVICE_BOT = "bot";
    public static final String DEVICE_PC = "pc";
    public static final String DEVICE_UNKNOWN = "unknown";

    private UserAgentParser() {
    }

    public static String parse(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return DEVICE_UNKNOWN;
        }
        String ua = userAgent.toLowerCase();
        if (ua.contains("bot") || ua.contains("spider") || ua.contains("crawler")) {
            return DEVICE_BOT;
        }
        if (ua.contains("ipad") || ua.contains("tablet")) {
            return DEVICE_TABLET;
        }
        if (ua.contains("mobile") || ua.contains("android") || ua.contains("iphone")) {
            return DEVICE_MOBILE;
        }
        return DEVICE_PC;
    }
}
