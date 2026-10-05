package com.shortlink.cache;

import com.shortlink.dto.LinkCacheDTO;
import com.shortlink.entity.LinkDO;

/**
 * 短链缓存值装配（V3.2 抽出）：跳转回填与异步刷新共用同一份装配逻辑，
 * 避免两处赋值不一致导致逻辑过期时间漏设。
 */
public final class LinkCacheAssembler {

    private LinkCacheAssembler() {
    }

    /**
     * @param logicalExpireSeconds 逻辑过期时长（秒）；<=0 表示不启用逻辑过期（该字段留空）
     */
    public static LinkCacheDTO toCacheDTO(LinkDO link, long logicalExpireSeconds) {
        LinkCacheDTO dto = new LinkCacheDTO();
        dto.setOriginalUrl(link.getOriginalUrl());
        dto.setEnableStatus(link.getEnableStatus());
        dto.setValidType(link.getValidType());
        dto.setValidDate(link.getValidDate());
        if (logicalExpireSeconds > 0) {
            dto.setLogicalExpireAt(System.currentTimeMillis() + logicalExpireSeconds * 1000);
        }
        return dto;
    }
}
