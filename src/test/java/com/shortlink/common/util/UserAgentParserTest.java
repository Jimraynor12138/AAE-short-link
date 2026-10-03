package com.shortlink.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * UA 解析单元测试
 */
class UserAgentParserTest {

    @Test
    void parsesMobile() {
        assertEquals(UserAgentParser.DEVICE_MOBILE,
                UserAgentParser.parse("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0) Mobile Safari"));
    }

    @Test
    void parsesTablet() {
        assertEquals(UserAgentParser.DEVICE_TABLET,
                UserAgentParser.parse("Mozilla/5.0 (iPad; CPU OS 17_0) Safari"));
    }

    @Test
    void parsesBot() {
        assertEquals(UserAgentParser.DEVICE_BOT,
                UserAgentParser.parse("Googlebot/2.1 (+http://www.google.com/bot.html)"));
    }

    @Test
    void parsesPc() {
        assertEquals(UserAgentParser.DEVICE_PC,
                UserAgentParser.parse("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120"));
    }

    @Test
    void blankIsUnknown() {
        assertEquals(UserAgentParser.DEVICE_UNKNOWN, UserAgentParser.parse(null));
        assertEquals(UserAgentParser.DEVICE_UNKNOWN, UserAgentParser.parse("  "));
    }
}
