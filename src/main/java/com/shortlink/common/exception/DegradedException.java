package com.shortlink.common.exception;

/**
 * 降级保护拒绝异常（V3.3 防雪崩）。
 *
 * 触发条件：依赖的缓存层故障（降级态）且回源并发已超过闸门上限。
 * 语义上等同于「服务繁忙」，由全局异常处理转换为 503，让客户端快速失败或重试，
 * 而不是让请求继续排队把数据库与线程池拖垮。
 */
public class DegradedException extends RuntimeException {

    public DegradedException(String message) {
        super(message);
    }
}
