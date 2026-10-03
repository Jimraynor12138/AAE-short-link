package com.shortlink.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shortlink.entity.LinkStatsDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 短链统计 Mapper（V2）。
 */
public interface LinkStatsMapper extends BaseMapper<LinkStatsDO> {

    /**
     * 按 (link_id, date) 唯一键做 upsert，写入当日统计快照。
     *
     * 语义说明：
     * 1. 快照覆盖而非累加：Redis 里的当日计数才是实时口径，重复写入结果一致（幂等），
     *    不会因重试导致多加。
     * 2. 单调保护（重要）：只有「新快照的 pv 不小于库中 pv」时才覆盖其余字段。
     *    否则一旦 Redis 丢失当日计数（重启且无持久化、被清空），pv 从 0 重新计数，
     *    会把 MySQL 里已经落库的历史数据回退成更小的值 —— 已持久化的数据被抹掉。
     *
     * 语法说明（两个 MySQL 细节，都是踩过的坑）：
     * 1. 使用 MySQL 8.0.19+ 的行别名（AS new）；旧的 VALUES(col) 在 8.0.20 起已废弃。
     * 2. 用了行别名之后，**旧行字段必须加表名前缀**（t_link_stats.pv），
     *    否则报 `Column 'pv' in field list is ambiguous`（与别名 new 冲突）。
     * 3. MySQL 的 ON DUPLICATE KEY UPDATE 赋值按「从左到右」生效，因此把 pv 放在最后赋值，
     *    保证前面的 IF 条件读到的都是更新前的旧 pv。
     */
    @Insert("""
            INSERT INTO t_link_stats
                (link_id, code, `date`, pv, uv, ip_cnt, default_cnt, referer_stats, device_stats)
            VALUES
                (#{stats.linkId}, #{stats.code}, #{stats.date}, #{stats.pv}, #{stats.uv},
                 #{stats.ipCnt}, #{stats.defaultCnt}, #{stats.refererStats}, #{stats.deviceStats})
            AS new
            ON DUPLICATE KEY UPDATE
                uv = IF(new.pv >= t_link_stats.pv, new.uv, t_link_stats.uv),
                ip_cnt = IF(new.pv >= t_link_stats.pv, new.ip_cnt, t_link_stats.ip_cnt),
                default_cnt = IF(new.pv >= t_link_stats.pv, new.default_cnt, t_link_stats.default_cnt),
                referer_stats = IF(new.pv >= t_link_stats.pv, new.referer_stats, t_link_stats.referer_stats),
                device_stats = IF(new.pv >= t_link_stats.pv, new.device_stats, t_link_stats.device_stats),
                pv = IF(new.pv >= t_link_stats.pv, new.pv, t_link_stats.pv)
            """)
    int upsertStats(@Param("stats") LinkStatsDO stats);
}
