package com.shortlink.idgenerator;

/**
 * 时钟回拨超限异常（V3.5 雪花算法）。
 *
 * 语义：检测到机器时钟回拨幅度超过 `shortlink.snowflake.max-backward-millis`，
 * 继续发号会生成重复 ID，因此**主动拒绝发号**。
 *
 * 为什么宁可失败也不"将就发号"：
 * - 重复 ID 会被 t_link 的 (domain, code) 唯一索引拦截，用户看到的是"创建失败"，
 *   但排查时现象是"随机偶发创建失败"，比明确报错难定位得多
 * - 生产环境的正确做法：告警 + 人工介入（或换一个未使用的 workerId 兜底），
 *   而不是在代码里悄悄降级
 */
public class ClockBackwardsException extends RuntimeException {

    public ClockBackwardsException(String message) {
        super(message);
    }
}
