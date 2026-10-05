package com.shortlink.service;

/**
 * 缓存异步刷新服务（V3.2 防击穿）。
 *
 * 触发场景：命中的缓存值已「逻辑过期」时，请求线程立即返回旧值并调用本服务异步重建，
 * 从而避免热点短码在缓存过期瞬间把请求线程全部压在 DB 上。
 *
 * 实现约定：本方法必须快速返回且**不抛异常**（刷新失败只保留旧值，等下一次触发）。
 */
public interface CacheRefreshService {

    /**
     * 异步刷新指定短码的缓存（内部有单飞控制，重复调用不会造成并发重建）
     */
    void refreshAsync(String code);
}
