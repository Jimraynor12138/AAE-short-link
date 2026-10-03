package com.shortlink.service;

import java.time.LocalDate;

/**
 * 统计快照落库服务（V2）。
 *
 * 设计要点：Redis 是「当日实时口径」，MySQL 只存定时快照。
 * 消费端每处理一条消息只做两件轻量事：写 Redis + 标记「这个短码脏了」；
 * 真正的落库由定时任务批量完成，避免每条消息一次 DB 写。
 */
public interface StatsSnapshotService {

    /**
     * 标记某短码某日的统计已变化（下次 flush 时需要落库）
     *
     * @param date 该条访问所属的统计日期（取自消息时间，而非 flush 时刻，避免跨零点丢数据）
     */
    void markDirty(String code, LocalDate date);

    /**
     * 把脏数据的当日统计快照写入 MySQL
     */
    void flush();

    /**
     * 扫描 Redis 当日统计 key，把有数据的短码重新标记为脏（自愈重启导致的脏标记丢失）
     *
     * @return 本次重新标记的短码数
     */
    int rescan();
}
