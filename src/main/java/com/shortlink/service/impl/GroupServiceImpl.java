package com.shortlink.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shortlink.common.exception.BizException;
import com.shortlink.dao.GroupMapper;
import com.shortlink.dto.GroupCreateReqDTO;
import com.shortlink.dto.GroupRespDTO;
import com.shortlink.entity.GroupDO;
import com.shortlink.service.GroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 短链分组业务实现
 */
@Service
@RequiredArgsConstructor
public class GroupServiceImpl implements GroupService {

    private final GroupMapper groupMapper;

    @Override
    public String createGroup(GroupCreateReqDTO reqDTO) {
        GroupDO group = new GroupDO();
        group.setGid(IdUtil.nanoId(8));
        group.setName(reqDTO.getName());
        group.setSortOrder(reqDTO.getSortOrder() == null ? 0 : reqDTO.getSortOrder());
        groupMapper.insert(group);
        return group.getGid();
    }

    @Override
    public List<GroupRespDTO> listGroup() {
        QueryWrapper<GroupDO> wrapper = new QueryWrapper<GroupDO>().orderByAsc("sort_order");
        return groupMapper.selectList(wrapper).stream().map(this::toRespDTO).toList();
    }

    @Override
    public void deleteGroup(String gid) {
        GroupDO group = groupMapper.selectOne(new QueryWrapper<GroupDO>().eq("gid", gid).last("LIMIT 1"));
        if (group == null) {
            throw new BizException("分组不存在或已删除");
        }
        groupMapper.deleteById(group.getId());
    }

    private GroupRespDTO toRespDTO(GroupDO group) {
        GroupRespDTO resp = new GroupRespDTO();
        resp.setId(group.getId());
        resp.setGid(group.getGid());
        resp.setName(group.getName());
        resp.setSortOrder(group.getSortOrder());
        resp.setCreateTime(group.getCreateTime());
        return resp;
    }
}
