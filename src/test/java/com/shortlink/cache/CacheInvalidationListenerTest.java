package com.shortlink.cache;

import com.shortlink.config.ShortLinkProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 本地缓存失效广播订阅者测试（V3.3）
 */
@ExtendWith(MockitoExtension.class)
class CacheInvalidationListenerTest {

    @Mock
    private LocalLinkCache localLinkCache;

    private CacheInvalidationListener listener;

    @BeforeEach
    void setUp() {
        ShortLinkProperties properties = new ShortLinkProperties();
        properties.setDomain("localhost:8080");
        listener = new CacheInvalidationListener(localLinkCache, properties);
    }

    private Message message(String body) {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }

    @Test
    void invalidatesLocalCacheForReceivedCode() {
        listener.onMessage(message("abc123"), null);

        verify(localLinkCache).invalidate("short-link:link:localhost:8080:abc123");
    }

    @Test
    void ignoresBlankMessage() {
        listener.onMessage(message("  "), null);

        verify(localLinkCache, never()).invalidate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void invalidateFailureIsSwallowed() {
        // 广播处理失败只影响缓存新鲜度，绝不能影响服务运行
        doThrow(new RuntimeException("boom")).when(localLinkCache).invalidate(org.mockito.ArgumentMatchers.anyString());

        assertDoesNotThrow(() -> listener.onMessage(message("abc123"), null));
    }
}
