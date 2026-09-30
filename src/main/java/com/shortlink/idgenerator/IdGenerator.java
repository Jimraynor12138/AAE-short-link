package com.shortlink.idgenerator;

/**
 * 发号器抽象接口（本项目核心可插拔组件）。
 *
 * <p>各版本实现：
 * <ul>
 *   <li>V0 {@code AutoIncrementIdGenerator}：数据库自增，实现最简</li>
 *   <li>V1 {@code RedisIncrIdGenerator}：Redis INCR，摆脱 DB 发号瓶颈</li>
 *   <li>V3 {@code SegmentIdGenerator}：号段模式（双 buffer），生产主流</li>
 *   <li>V3 {@code SnowflakeIdGenerator}：雪花算法，本地生成不依赖存储</li>
 * </ul>
 * 通过配置 shortlink.id-generator-type 切换，产出统一由 {@link com.shortlink.codec.Base62Codec} 编码为短码。
 */
public interface IdGenerator {

    /**
     * 获取下一个全局唯一 ID
     *
     * @return 严格大于 0 的唯一数
     */
    long nextId();
}
