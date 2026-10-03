package com.shortlink.service;

/**
 * 访问记录入口（V2 新增）。
 *
 * 跳转链路只依赖这个接口，具体走「MQ 异步」还是「同步直写」由配置决定：
 * - AsyncVisitRecorder：MQ 可用时的正常路径，跳转只投递消息
 * - SyncVisitRecorder：MQ 关闭时的降级路径（等价 V1 行为）
 * 这保证了「中间件故障不影响跳转」这一核心原则。
 */
public interface VisitRecorder {

    void record(String code, String clientIp, String userAgent, String referer);
}
