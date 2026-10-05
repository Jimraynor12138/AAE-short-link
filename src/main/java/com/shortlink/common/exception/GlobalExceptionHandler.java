package com.shortlink.common.exception;

import com.shortlink.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：把所有异常统一转换为 Result 结构
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e) {
        return Result.failure(e.getCode(), e.getMessage());
    }

    /**
     * 参数校验异常（@Valid 校验失败）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .findFirst()
                .orElse("参数校验失败");
        return Result.failure("A0400", message);
    }

    /**
     * 降级保护拒绝（V3.3）：依赖（缓存层）故障且回源并发已满时快速失败。
     * 这里返回 503 而不是 200 + 业务错误码，让网关/客户端能按"服务不可用"处理（重试/降级）
     */
    @ExceptionHandler(DegradedException.class)
    public ResponseEntity<Result<Void>> handleDegradedException(DegradedException e) {
        log.warn("服务处于降级态，请求被限流: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.failure("B0503", e.getMessage()));
    }

    /**
     * 兜底异常：不向客户端暴露堆栈
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.failure("B0001", "系统繁忙，请稍后重试");
    }
}
