-- ============================================================
-- 高性能短链接系统 建表脚本（MySQL 8）
-- 执行方式：先 CREATE DATABASE short_link，再执行本脚本
-- ============================================================

CREATE DATABASE IF NOT EXISTS `short_link` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `short_link`;

-- ------------------------------------------------------------
-- 1. 发号器序列表（V0：数据库自增发号）
--    每次 INSERT 产生一个新号，MyBatis 通过 RETURN_GENERATED_KEYS 取回。
--    说明：表会持续增长，V1 起切换为 Redis INCR 后该表不再使用（保留作为兜底）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_sequence` (
    `id`     BIGINT NOT NULL AUTO_INCREMENT COMMENT '发号器产生的全局唯一 ID',
    `stub`   TINYINT NOT NULL DEFAULT 0 COMMENT '占位字段，无业务含义',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '发号器序列表';

-- ------------------------------------------------------------
-- 2. 短链接表（核心表：跳转链路唯一依赖）
--    id 即发号器产出的号，code = Base62(id)，二者可互相推导；
--    保留 (domain, code) 唯一索引，为未来多域名做准备。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_link` (
    `id`            BIGINT       NOT NULL COMMENT '全局唯一 ID（发号器产生，IdType.INPUT）',
    -- 短码必须区分大小写（COLLATE utf8mb4_bin）：Base62 字符集同时含 a-z 与 A-Z，
    -- 若沿用默认的 utf8mb4_general_ci（大小写不敏感），"2A" 会被判为与 "2a" 重复，
    -- 等于白白浪费一半码空间，且 /2A 可能命中 /2a 的记录。
    `code`          VARCHAR(16)  COLLATE utf8mb4_bin NOT NULL COMMENT '短码（Base62 编码，区分大小写）',
    `domain`        VARCHAR(128) NOT NULL COMMENT '短链域名',
    `original_url`  VARCHAR(768) NOT NULL COMMENT '原始长 URL',
    `gid`           VARCHAR(32)  NOT NULL DEFAULT 'default' COMMENT '分组标识',
    `enable_status` TINYINT      NOT NULL DEFAULT 0 COMMENT '启用状态：0 启用，1 停用（停用后跳转拦截）',
    `valid_type`    TINYINT      NOT NULL DEFAULT 1 COMMENT '有效期类型：1 永久，2 自定义',
    `valid_date`    DATETIME     NULL COMMENT '过期时间（valid_type=2 时必填）',
    `description`   VARCHAR(512) NULL COMMENT '描述',
    `del_flag`      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删，1 已删',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_domain_code` (`domain`, `code`),
    KEY `idx_gid_create` (`gid`, `del_flag`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '短链接表';

-- ------------------------------------------------------------
-- 3. 访问统计聚合表（V1 起使用）
--    按 link_id + date 聚合，写入量从“每次点击一行”降为“每日每链一行”；
--    维度明细（来源/设备/地域）以 JSON 文本存储，V2 消费端聚合时写入。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_link_stats` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `link_id`       BIGINT      NOT NULL COMMENT '短链 ID',
    -- 与 t_link.code 保持一致：Base62 短码含大小写，必须区分（否则 2A/2a 在统计维度上会串）
    `code`          VARCHAR(16) COLLATE utf8mb4_bin NOT NULL COMMENT '短码（Base62 编码，区分大小写）',
    `date`          DATE        NOT NULL COMMENT '统计日期',
    `pv`            BIGINT      NOT NULL DEFAULT 0 COMMENT '点击量',
    `uv`            BIGINT      NOT NULL DEFAULT 0 COMMENT '独立访客数',
    `ip_cnt`        BIGINT      NOT NULL DEFAULT 0 COMMENT '独立 IP 数',
    `default_cnt`   BIGINT      NOT NULL DEFAULT 0 COMMENT '直接访问（无 Referer）次数',
    `referer_stats` VARCHAR(512) NULL COMMENT '来源分布 JSON，如 {"www.baidu.com":10}',
    `device_stats`  VARCHAR(512) NULL COMMENT '设备分布 JSON，如 {"mobile":8,"pc":2}',
    `locale_stats`  VARCHAR(512) NULL COMMENT '地域分布 JSON（V2 引入 IP 解析后填充）',
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_link_date` (`link_id`, `date`),
    KEY `idx_code_date` (`code`, `date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '短链访问统计聚合表';

-- ------------------------------------------------------------
-- 4. 短链分组表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_group` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gid`         VARCHAR(32) NOT NULL COMMENT '分组标识（随机生成）',
    `name`        VARCHAR(64) NOT NULL COMMENT '分组名称',
    `sort_order`  INT         NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    `del_flag`    TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删，1 已删',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_gid` (`gid`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '短链分组表';

-- ------------------------------------------------------------
-- 5. 用户表（V0/V1 极简：单管理员，密码为 BCrypt 摘要，V1 登录模块接入）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`    VARCHAR(64)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(128) NOT NULL COMMENT '密码（BCrypt 摘要，不存明文）',
    `real_name`   VARCHAR(64)  NULL COMMENT '真实姓名',
    `del_flag`    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删，1 已删',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

-- ------------------------------------------------------------
-- 初始数据：默认分组
-- ------------------------------------------------------------
INSERT IGNORE INTO `t_group` (`gid`, `name`, `sort_order`)
VALUES ('default', '默认分组', 0);

-- ------------------------------------------------------------
-- 6. 号段分配表（V3.5：号段模式发号）
--    一行一个业务：max_id = 已分配到的最大号；一次发号 = 一次 UPDATE（按主键行锁保证原子）。
--    与 V0 的 t_sequence（每发一号 INSERT 一行）对比：
--      - 发号的 DB 压力降为 1/step（默认 1/1000）
--      - 表不再增长，永远只有一行
--    max_id 的初始值会在应用启动时抬升到「不小于 t_link 当前最大 id」，避免与其他发号器切换时重号。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_segment` (
    `biz_tag`     VARCHAR(64) NOT NULL COMMENT '业务标识（一行一个业务）',
    `max_id`      BIGINT      NOT NULL DEFAULT 0 COMMENT '当前已分配到的最大号',
    `step`        INT         NOT NULL DEFAULT 1000 COMMENT '号段长度（一次分配的号数）',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`biz_tag`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '号段分配表';

INSERT IGNORE INTO `t_segment` (`biz_tag`, `max_id`, `step`)
VALUES ('short-link', 0, 1000);
