package com.shortlink.service;

/**
 * 短链跳转服务接口（V1.1 从 LinkService 拆出）
 */
public interface RedirectService {

    /**
     * 跳转解析：查 Redis 缓存，未命中查 DB 并回填，最后做停用/过期校验，
     * 成功后记录访问（V2 起默认投递 MQ 消息，MQ 关闭时降级为同步写入）
     *
     * @param code      短码
     * @param clientIp  客户端 IP（用于独立 IP 统计）
     * @param userAgent 浏览器 UA（参与访客身份与设备统计）
     * @param referer   来源页（用于来源分布统计）
     * @return 可跳转的原始 URL；短码不存在、已删除、已停用、已过期时返回 null
     */
    String resolveRedirectUrl(String code, String clientIp, String userAgent, String referer);
}
