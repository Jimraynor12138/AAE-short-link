package com.shortlink.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortlink.common.result.Result;
import com.shortlink.common.util.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 写接口限流拦截器（V3.3 防雪崩）。
 *
 * 只对写请求（POST/PUT/DELETE）限流：读跳转链路用降级闸门保护 DB 更合适，
 * 而写接口被刷会直接消耗发号器、唯一索引与 DB 写入能力。
 *
 * 限流维度用客户端 IP：学习项目够用；真实生产通常还会叠加用户/租户维度与全局阈值。
 * 被限流返回 429（Too Many Requests），并保持与业务接口一致的 Result 结构。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final SlidingWindowRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!isWriteRequest(request.getMethod())) {
            return true;
        }
        String clientIp = ClientIpResolver.resolve(request);
        if (rateLimiter.tryAcquire("write:" + clientIp)) {
            return true;
        }

        log.warn("写接口触发限流: ip={}, method={}, path={}", clientIp, request.getMethod(), request.getRequestURI());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.failure("A0429", "请求过于频繁，请稍后重试")));
        return false;
    }

    private boolean isWriteRequest(String method) {
        return HttpMethod.POST.matches(method)
                || HttpMethod.PUT.matches(method)
                || HttpMethod.DELETE.matches(method);
    }
}
