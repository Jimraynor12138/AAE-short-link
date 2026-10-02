package com.shortlink.service.impl;

import com.shortlink.codec.Base62Codec;
import com.shortlink.common.exception.BizException;
import com.shortlink.config.ShortLinkProperties;
import com.shortlink.dao.LinkMapper;
import com.shortlink.dto.LinkCreateReqDTO;
import com.shortlink.entity.LinkDO;
import com.shortlink.idgenerator.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 创建短链单元测试：验证发号、Base62编码、落库的完整流程与参数校验
 */
@ExtendWith(MockitoExtension.class)
class LinkServiceImplCreateTest {

    @Mock
    private LinkMapper linkMapper;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private LinkServiceImpl linkService;

    @BeforeEach
    void setUp() {
        ShortLinkProperties properties = new ShortLinkProperties();
        properties.setDomain("localhost:8080");
        linkService = new LinkServiceImpl(linkMapper, idGenerator, properties, stringRedisTemplate);
    }

    private LinkCreateReqDTO buildReq(String url, Integer validType) {
        LinkCreateReqDTO req = new LinkCreateReqDTO();
        req.setOriginalUrl(url);
        req.setValidType(validType);
        return req;
    }

    @Test
    void createEncodesIdToBase62Code() {
        when(idGenerator.nextId()).thenReturn(1000L);

        LinkCreateReqDTO req = buildReq("https://example.com/a?b=1", 1);
        var resp = linkService.createLink(req);

        // 1000 = 62*16 + 8, Base62 编码为 g8
        assertEquals("g8", resp.getCode());
        assertEquals("http://localhost:8080/g8", resp.getFullShortUrl());

        ArgumentCaptor<LinkDO> captor = ArgumentCaptor.forClass(LinkDO.class);
        verify(linkMapper).insert(captor.capture());
        assertEquals(1000L, captor.getValue().getId());
        assertEquals("g8", captor.getValue().getCode());
        assertEquals("default", captor.getValue().getGid());
        assertEquals(0, captor.getValue().getEnableStatus());
    }

    @Test
    void createRejectsInvalidUrl() {
        assertThrows(BizException.class, () -> linkService.createLink(buildReq("ftp://example.com", 1)));
        assertThrows(BizException.class, () -> linkService.createLink(buildReq("notaurl", 1)));
    }

    @Test
    void createRejectsCustomValidTypeWithoutDate() {
        LinkCreateReqDTO req = buildReq("https://example.com", 2);
        assertThrows(BizException.class, () -> linkService.createLink(req));
    }

    @Test
    void createRejectsUnknownValidType() {
        assertThrows(BizException.class, () -> linkService.createLink(buildReq("https://example.com", 3)));
    }

    @Test
    void createThrowsBizExceptionNotNpeWhenValidTypeNull() {
        // 服务层被内部代码直接调用（绕过 Controller @NotNull）时也不应 NPE
        assertThrows(BizException.class, () -> linkService.createLink(buildReq("https://example.com", null)));
    }

    @Test
    void createAcceptsLocalhostWithoutDot() {
        // 无点主机名（localhost / 纯 IP）应放行
        when(idGenerator.nextId()).thenReturn(1000L);
        assertDoesNotThrow(() -> linkService.createLink(buildReq("http://localhost:8080/index.html", 1)));
        assertDoesNotThrow(() -> linkService.createLink(buildReq("http://127.0.0.1:8080/a?b=1+c,d;e", 1)));
    }

    @Test
    void createRejectsUrlWithWhitespace() {
        // 空白字符会导致 URI.create 抛异常，必须在入口拦截
        assertThrows(BizException.class, () -> linkService.createLink(buildReq("https://example.com/a b", 1)));
    }

    @Test
    void createRetriesWithNewIdWhenCodeConflicts() {
        // V1.2 起号源是 Redis INCR，丢号可能重号，唯一索引拦截后应重新发号重试
        when(idGenerator.nextId()).thenReturn(1000L, 1001L);
        when(linkMapper.insert(any(LinkDO.class)))
                .thenThrow(new DuplicateKeyException("duplicated code"))
                .thenReturn(1);

        var resp = linkService.createLink(buildReq("https://example.com", 1));

        assertEquals(Base62Codec.encode(1001L), resp.getCode());
        verify(linkMapper, times(2)).insert(any(LinkDO.class));
    }

    @Test
    void createFailsAfterMaxRetries() {
        when(idGenerator.nextId()).thenReturn(1000L);
        when(linkMapper.insert(any(LinkDO.class))).thenThrow(new DuplicateKeyException("duplicated code"));

        assertThrows(BizException.class, () -> linkService.createLink(buildReq("https://example.com", 1)));
        verify(linkMapper, times(3)).insert(any(LinkDO.class));
    }
}
