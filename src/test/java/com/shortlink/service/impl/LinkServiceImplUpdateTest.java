package com.shortlink.service.impl;

import com.shortlink.bloom.ShortLinkBloomFilter;
import com.shortlink.cache.CacheInvalidationPublisher;
import com.shortlink.cache.LocalLinkCache;
import com.shortlink.common.exception.BizException;
import com.shortlink.config.ShortLinkProperties;
import com.shortlink.dao.LinkMapper;
import com.shortlink.dto.LinkUpdateReqDTO;
import com.shortlink.entity.LinkDO;
import com.shortlink.idgenerator.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 修改/删除短链单元测试：覆盖存在性校验、服务层兜底校验、字段合并逻辑
 */
@ExtendWith(MockitoExtension.class)
class LinkServiceImplUpdateTest {

    @Mock
    private LinkMapper linkMapper;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ShortLinkBloomFilter bloomFilter;

    @Mock
    private LocalLinkCache localLinkCache;

    @Mock
    private CacheInvalidationPublisher cacheInvalidationPublisher;

    private LinkServiceImpl linkService;

    @BeforeEach
    void setUp() {
        ShortLinkProperties properties = new ShortLinkProperties();
        properties.setDomain("localhost:8080");
        linkService = new LinkServiceImpl(linkMapper, idGenerator, properties, stringRedisTemplate, bloomFilter,
                localLinkCache, cacheInvalidationPublisher);
    }

    private LinkDO buildExistLink() {
        LinkDO link = new LinkDO();
        link.setId(100L);
        link.setCode("1cW");
        link.setDomain("localhost:8080");
        link.setOriginalUrl("https://example.com/old");
        link.setGid("default");
        link.setEnableStatus(0);
        link.setValidType(1);
        link.setDelFlag(0);
        return link;
    }

    private LinkUpdateReqDTO buildUpdate(Long id) {
        LinkUpdateReqDTO req = new LinkUpdateReqDTO();
        req.setId(id);
        return req;
    }

    @Test
    void updateThrowsWhenLinkNotFound() {
        when(linkMapper.selectById(999L)).thenReturn(null);
        assertThrows(BizException.class, () -> linkService.updateLink(buildUpdate(999L)));
    }

    @Test
    void updateRejectsInvalidUrl() {
        when(linkMapper.selectById(100L)).thenReturn(buildExistLink());
        LinkUpdateReqDTO req = buildUpdate(100L);
        req.setOriginalUrl("ftp://example.com");
        assertThrows(BizException.class, () -> linkService.updateLink(req));

        LinkUpdateReqDTO req2 = buildUpdate(100L);
        req2.setOriginalUrl("https://example.com/a b");
        assertThrows(BizException.class, () -> linkService.updateLink(req2));
    }

    @Test
    void updateRejectsInvalidValidType() {
        when(linkMapper.selectById(100L)).thenReturn(buildExistLink());
        LinkUpdateReqDTO req = buildUpdate(100L);
        req.setValidType(3);
        assertThrows(BizException.class, () -> linkService.updateLink(req));
    }

    @Test
    void updateRejectsInvalidEnableStatus() {
        when(linkMapper.selectById(100L)).thenReturn(buildExistLink());
        LinkUpdateReqDTO req = buildUpdate(100L);
        req.setEnableStatus(5);
        assertThrows(BizException.class, () -> linkService.updateLink(req));
    }

    @Test
    void updateRejectsCustomValidTypeWithoutDate() {
        when(linkMapper.selectById(100L)).thenReturn(buildExistLink());
        LinkUpdateReqDTO req = buildUpdate(100L);
        req.setValidType(2);
        assertThrows(BizException.class, () -> linkService.updateLink(req));
    }

    @Test
    void updateMergesFieldsAndWritesValidDate() {
        when(linkMapper.selectById(100L)).thenReturn(buildExistLink());
        LocalDateTime validDate = LocalDateTime.now().plusDays(7);
        LinkUpdateReqDTO req = buildUpdate(100L);
        req.setValidType(2);
        req.setValidDate(validDate);

        linkService.updateLink(req);

        ArgumentCaptor<LinkDO> captor = ArgumentCaptor.forClass(LinkDO.class);
        verify(linkMapper).updateById(captor.capture());
        assertEquals(2, captor.getValue().getValidType());
        assertEquals(validDate, captor.getValue().getValidDate());
    }

    @Test
    void updateCustomToDateKeepsExistValidDate() {
        LinkDO exist = buildExistLink();
        exist.setValidType(2);
        exist.setValidDate(LocalDateTime.now().plusDays(3));
        when(linkMapper.selectById(100L)).thenReturn(exist);

        LinkUpdateReqDTO req = buildUpdate(100L);
        req.setOriginalUrl("https://example.com/new");
        linkService.updateLink(req);

        ArgumentCaptor<LinkDO> captor = ArgumentCaptor.forClass(LinkDO.class);
        verify(linkMapper).updateById(captor.capture());
        assertEquals("https://example.com/new", captor.getValue().getOriginalUrl());
        assertEquals(2, captor.getValue().getValidType());
        assertEquals(exist.getValidDate(), captor.getValue().getValidDate());
    }

    @Test
    void updateSwitchToForeverClearsValidDateInUpdateObject() {
        LinkDO exist = buildExistLink();
        exist.setValidType(2);
        exist.setValidDate(LocalDateTime.now().plusDays(3));
        when(linkMapper.selectById(100L)).thenReturn(exist);

        LinkUpdateReqDTO req = buildUpdate(100L);
        req.setValidType(1);
        linkService.updateLink(req);

        ArgumentCaptor<LinkDO> captor = ArgumentCaptor.forClass(LinkDO.class);
        verify(linkMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getValidType());
        assertNull(captor.getValue().getValidDate());
    }

    @Test
    void deleteThrowsWhenLinkNotFound() {
        when(linkMapper.selectById(999L)).thenReturn(null);
        assertThrows(BizException.class, () -> linkService.deleteLink(999L));
    }

    @Test
    void deleteCallsMapperWhenLinkExists() {
        when(linkMapper.selectById(100L)).thenReturn(buildExistLink());
        linkService.deleteLink(100L);
        verify(linkMapper).deleteById(100L);
    }

    @Test
    void updateEvictsLinkCache() {
        // Cache Aside：修改后必须删除两级缓存，并广播其他实例清 L1
        when(linkMapper.selectById(100L)).thenReturn(buildExistLink());
        LinkUpdateReqDTO req = buildUpdate(100L);
        req.setOriginalUrl("https://example.com/new");
        linkService.updateLink(req);
        verify(stringRedisTemplate).delete("short-link:link:localhost:8080:1cW");
        verify(localLinkCache).invalidate("short-link:link:localhost:8080:1cW");
        verify(cacheInvalidationPublisher).publish("1cW");
    }

    @Test
    void deleteEvictsLinkCache() {
        when(linkMapper.selectById(100L)).thenReturn(buildExistLink());
        linkService.deleteLink(100L);
        verify(stringRedisTemplate).delete("short-link:link:localhost:8080:1cW");
        verify(localLinkCache).invalidate("short-link:link:localhost:8080:1cW");
        verify(cacheInvalidationPublisher).publish("1cW");
    }

    @Test
    void cacheEvictFailureDoesNotBreakUpdate() {
        // 删缓存失败不阻断主流程（DB 是事实源，TTL 兜底）
        when(linkMapper.selectById(100L)).thenReturn(buildExistLink());
        when(stringRedisTemplate.delete("short-link:link:localhost:8080:1cW"))
                .thenThrow(new RuntimeException("redis down"));
        LinkUpdateReqDTO req = buildUpdate(100L);
        req.setOriginalUrl("https://example.com/new");
        linkService.updateLink(req);
        verify(linkMapper).updateById(any(LinkDO.class));
    }
}
