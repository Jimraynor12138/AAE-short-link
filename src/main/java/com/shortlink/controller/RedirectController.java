package com.shortlink.controller;

import com.shortlink.service.RedirectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 短链跳转接口（核心读链路）。
 *
 * <p>直接返回 302 + Location 头，响应体为空，使跳转开销最小化。
 */
@Tag(name = "短链跳转")
@RestController
@RequiredArgsConstructor
public class RedirectController {

    private final RedirectService redirectService;

    /**
     * 短码限制为 1~16 位字母数字，避免该路由吞掉其他静态路径
     */
    @Operation(summary = "短链 302 跳转")
    @GetMapping("/{code:[0-9a-zA-Z]{1,16}}")
    public ResponseEntity<Void> redirect(@PathVariable("code") String code) {
        String target = redirectService.resolveRedirectUrl(code);
        if (target == null) {
            // 不存在 / 已删除 / 已停用 / 已过期，统一 404，不泄露具体原因
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(target))
                    .build();
        } catch (IllegalArgumentException e) {
            // 防御：脏数据 URL 无法构造合法 Location 时按 404 处理，避免 500
            return ResponseEntity.notFound().build();
        }
    }
}
