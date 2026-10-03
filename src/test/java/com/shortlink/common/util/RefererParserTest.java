package com.shortlink.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Referer 解析单元测试
 */
class RefererParserTest {

    @Test
    void extractsHost() {
        assertEquals("www.baidu.com", RefererParser.parseHost("https://www.baidu.com/s?wd=redis"));
        assertEquals("localhost", RefererParser.parseHost("http://localhost:8080/doc.html"));
    }

    @Test
    void blankOrNullIsDirect() {
        assertEquals(RefererParser.DIRECT, RefererParser.parseHost(null));
        assertEquals(RefererParser.DIRECT, RefererParser.parseHost("   "));
    }

    @Test
    void invalidRefererIsDirect() {
        assertEquals(RefererParser.DIRECT, RefererParser.parseHost("not a url"));
    }
}
