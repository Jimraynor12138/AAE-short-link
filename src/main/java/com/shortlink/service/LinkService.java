package com.shortlink.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.shortlink.dto.LinkCreateReqDTO;
import com.shortlink.dto.LinkPageReqDTO;
import com.shortlink.dto.LinkRespDTO;
import com.shortlink.dto.LinkUpdateReqDTO;

/**
 * 短链接业务接口
 */
public interface LinkService {

    /**
     * 创建短链：发号 → Base62 编码 → 落库
     */
    LinkRespDTO createLink(LinkCreateReqDTO reqDTO);

    /**
     * 修改短链（域名/短码不可改）
     */
    void updateLink(LinkUpdateReqDTO reqDTO);

    /**
     * 删除短链（逻辑删除）
     */
    void deleteLink(Long id);

    /**
     * 分页查询短链
     */
    IPage<LinkRespDTO> pageLink(LinkPageReqDTO reqDTO);
}
