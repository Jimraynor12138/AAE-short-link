package com.shortlink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shortlink.cache.CacheKeyBuilder;
import com.shortlink.codec.Base62Codec;
import com.shortlink.common.exception.BizException;
import com.shortlink.config.ShortLinkProperties;
import com.shortlink.dao.LinkMapper;
import com.shortlink.dto.LinkCreateReqDTO;
import com.shortlink.dto.LinkPageReqDTO;
import com.shortlink.dto.LinkRespDTO;
import com.shortlink.dto.LinkUpdateReqDTO;
import com.shortlink.entity.LinkDO;
import com.shortlink.idgenerator.IdGenerator;
import com.shortlink.service.LinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 短链接管理业务实现（V1.1：跳转逻辑已拆至 RedirectServiceImpl，本类负责 CRUD 与缓存一致性维护）。
 *
 * 缓存策略（Cache Aside）：
 * 创建不写缓存（懒加载，首次访问回填）；修改/删除「先改库、后删缓存」，
 * 删缓存失败仅记日志——脏数据最多存活到 TTL 过期，由 TTL 兜底最终一致性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LinkServiceImpl implements LinkService {

    /** 分组缺省值 */
    public static final String DEFAULT_GID = "default";

    /**
     * 长 URL 格式校验：
     * 协议限定 http/https；主机名允许无点的 localhost / 纯 IP；
     * 路径与查询串放行为任意非空白字符（兼容 + , ; 等常见 query 字符），
     * 拒绝空白字符可保证 URI.create 不抛异常。
     */
    private static final Pattern URL_PATTERN =
            Pattern.compile("^https?://[\\w-]+(\\.[\\w-]+)*(:\\d+)?(/\\S*)?$");

    private final LinkMapper linkMapper;
    private final IdGenerator idGenerator;
    private final ShortLinkProperties properties;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public LinkRespDTO createLink(LinkCreateReqDTO reqDTO) {
        validateCreate(reqDTO);

        long id = idGenerator.nextId();
        LinkDO link = new LinkDO();
        link.setId(id);
        link.setCode(Base62Codec.encode(id));
        link.setDomain(properties.getDomain());
        link.setOriginalUrl(reqDTO.getOriginalUrl());
        link.setGid(reqDTO.getGid() == null || reqDTO.getGid().isBlank() ? DEFAULT_GID : reqDTO.getGid());
        link.setEnableStatus(0);
        link.setValidType(reqDTO.getValidType());
        link.setValidDate(reqDTO.getValidType() == 2 ? reqDTO.getValidDate() : null);
        link.setDescription(reqDTO.getDescription());

        linkMapper.insert(link);
        log.info("创建短链: id={}, code={}, url={}", link.getId(), link.getCode(), link.getOriginalUrl());
        return toRespDTO(link);
    }

    @Override
    public void updateLink(LinkUpdateReqDTO reqDTO) {
        LinkDO exist = linkMapper.selectById(reqDTO.getId());
        if (exist == null) {
            throw new BizException("短链不存在或已删除");
        }
        // 服务层兜底校验（防止绕过 Controller 的脏数据落库）：
        // 修改后的 URL 若提供，必须与创建时同样通过格式校验
        if (reqDTO.getOriginalUrl() != null && !URL_PATTERN.matcher(reqDTO.getOriginalUrl()).matches()) {
            throw new BizException("长链接格式不正确");
        }
        // 有效期类型 / 启用状态若提供，必须在合法取值域内
        if (reqDTO.getValidType() != null && reqDTO.getValidType() != 1 && reqDTO.getValidType() != 2) {
            throw new BizException("有效期类型不合法，仅支持 1-永久 2-自定义");
        }
        if (reqDTO.getEnableStatus() != null && reqDTO.getEnableStatus() != 0 && reqDTO.getEnableStatus() != 1) {
            throw new BizException("启用状态不合法，仅支持 0-启用 1-停用");
        }
        // 修改后仍需保证“自定义有效期必须带过期时间”的约束
        Integer validType = reqDTO.getValidType() != null ? reqDTO.getValidType() : exist.getValidType();
        LocalDateTime validDate = reqDTO.getValidDate() != null ? reqDTO.getValidDate() : exist.getValidDate();
        if (validType == 2 && validDate == null) {
            throw new BizException("自定义有效期必须指定过期时间");
        }

        LinkDO update = new LinkDO();
        update.setId(reqDTO.getId());
        update.setOriginalUrl(reqDTO.getOriginalUrl());
        update.setGid(reqDTO.getGid());
        update.setEnableStatus(reqDTO.getEnableStatus());
        // 使用合并后的 validType/validDate，保证“仅改其他字段”时有效期字段语义完整
        update.setValidType(validType);
        update.setValidDate(validType == 2 ? validDate : null);
        update.setDescription(reqDTO.getDescription());
        linkMapper.updateById(update);

        // Cache Aside：先改库、后删缓存（删失败仅记日志，TTL 兜底）
        evictLinkCache(exist.getCode());
    }

    @Override
    public void deleteLink(Long id) {
        LinkDO exist = linkMapper.selectById(id);
        if (exist == null) {
            throw new BizException("短链不存在或已删除");
        }
        // @TableLogic 生效：实际执行 UPDATE t_link SET del_flag=1
        linkMapper.deleteById(id);

        // Cache Aside：先删库、后删缓存
        evictLinkCache(exist.getCode());
    }

    /**
     * 删除短链缓存（含空值缓存）。失败不阻断主流程：DB 是事实源，TTL 到期后自动最终一致
     */
    private void evictLinkCache(String code) {
        try {
            stringRedisTemplate.delete(CacheKeyBuilder.buildLinkKey(properties.getDomain(), code));
        } catch (Exception e) {
            log.error("删除短链缓存失败, code={}", code, e);
        }
    }

    @Override
    public IPage<LinkRespDTO> pageLink(LinkPageReqDTO reqDTO) {
        QueryWrapper<LinkDO> wrapper = new QueryWrapper<LinkDO>()
                .eq(reqDTO.getGid() != null && !reqDTO.getGid().isBlank(), "gid", reqDTO.getGid())
                .orderByDesc("create_time");
        Page<LinkDO> page = linkMapper.selectPage(Page.of(reqDTO.getCurrent(), reqDTO.getSize()), wrapper);
        return page.convert(this::toRespDTO);
    }

    private void validateCreate(LinkCreateReqDTO reqDTO) {
        if (!URL_PATTERN.matcher(reqDTO.getOriginalUrl()).matches()) {
            throw new BizException("长链接格式不正确");
        }
        // 先做取值域校验（含 null 判断），再做 == 2 判断，避免自动拆箱 NPE
        if (reqDTO.getValidType() == null || (reqDTO.getValidType() != 1 && reqDTO.getValidType() != 2)) {
            throw new BizException("有效期类型不合法，仅支持 1-永久 2-自定义");
        }
        if (reqDTO.getValidType() == 2 && reqDTO.getValidDate() == null) {
            throw new BizException("自定义有效期必须指定过期时间");
        }
    }

    private LinkRespDTO toRespDTO(LinkDO link) {
        LinkRespDTO resp = new LinkRespDTO();
        resp.setId(link.getId());
        resp.setCode(link.getCode());
        resp.setFullShortUrl("http://" + properties.getDomain() + "/" + link.getCode());
        resp.setOriginalUrl(link.getOriginalUrl());
        resp.setGid(link.getGid());
        resp.setEnableStatus(link.getEnableStatus());
        resp.setValidType(link.getValidType());
        resp.setValidDate(link.getValidDate());
        resp.setDescription(link.getDescription());
        resp.setCreateTime(link.getCreateTime());
        return resp;
    }
}
