package com.shortlink.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 写接口限流拦截器测试（V3.3）：仅写请求生效、触发时返回 429
 */
@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    @Mock
    private SlidingWindowRateLimiter rateLimiter;

    private RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new RateLimitInterceptor(rateLimiter, new ObjectMapper());
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    @Test
    void writeRequestPassesWhenUnderLimit() throws Exception {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request("POST", "/api/v1/link/create"), response, new Object());

        assertTrue(allowed);
        assertEquals(200, response.getStatus());
    }

    @Test
    void writeRequestRejectedWith429WhenLimited() throws Exception {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request("POST", "/api/v1/link/create"), response, new Object());

        assertFalse(allowed);
        assertEquals(429, response.getStatus());
        assertTrue(response.getContentAsString().contains("A0429"));
    }

    @Test
    void getRequestNeverConsumesQuota() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 跳转链路是读请求：刻意不参与写限流（用降级闸门保护 DB）
        assertTrue(interceptor.preHandle(request("GET", "/api/v1/link/page"), response, new Object()));

        verify(rateLimiter, never()).tryAcquire(anyString());
    }
}
