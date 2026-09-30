package com.shortlink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 短链分组实体，对应表 t_group
 */
@Data
@TableName("t_group")
public class GroupDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分组标识（随机生成，业务侧唯一键） */
    private String gid;

    /** 分组名称 */
    private String name;

    /** 排序值，越小越靠前 */
    private Integer sortOrder;

    /** 逻辑删除：0 未删，1 已删 */
    @TableLogic
    private Integer delFlag;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
