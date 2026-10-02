package com.shortlink.service;

/**
 * 短链跳转服务接口（V1.1 从 LinkService 拆出）
 */
public interface RedirectService {

    /**
     * 跳转解析：查 Redis 缓存，未命中查 DB 并回填，最后做停用/过期校验，
     * 成功后同步记录访问统计（V1.3）
     *
     * @param code      短码
     * @param clientIp  客户端 IP（用于独立 IP 统计）
     * @param userAgent 浏览器 UA（参与访客身份计算）
     * @return 可跳转的原始 URL；短码不存在、已删除、已停用、已过期时返回 null
     */
    String resolveRedirectUrl(String code, String clientIp, String userAgent);
}
