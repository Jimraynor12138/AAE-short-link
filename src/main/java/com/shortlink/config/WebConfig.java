package com.shortlink.config;

import com.shortlink.common.ratelimit.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置（V3.3）：注册写接口限流拦截器。
 *
 * 只挂在管理接口上，跳转链路（/{code}）刻意不限流：
 * 那是核心读服务，用降级闸门（保护 DB）比用 QPS 限流更贴合场景，
 * 且真实生产里跳转链路的流量控制通常前置到 Nginx/CDN 完成。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/v1/link/**", "/api/v1/group/**");
    }
}
