package com.shortlink.service;

import com.shortlink.dto.GroupCreateReqDTO;
import com.shortlink.dto.GroupRespDTO;

import java.util.List;

/**
 * 短链分组业务接口
 */
public interface GroupService {

    /**
     * 创建分组，返回分组标识 gid
     */
    String createGroup(GroupCreateReqDTO reqDTO);

    /**
     * 查询全部分组（按 sortOrder 升序）
     */
    List<GroupRespDTO> listGroup();

    /**
     * 删除分组（逻辑删除，组内短链保留但归属不再展示）
     */
    void deleteGroup(String gid);
}
