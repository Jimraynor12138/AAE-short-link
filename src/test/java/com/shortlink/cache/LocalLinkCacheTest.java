package com.shortlink.cache;

import com.shortlink.config.ShortLinkProperties;
import com.shortlink.dto.LinkCacheDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地一级缓存测试（V3.3）：写入/读取/失效/开关/统计
 */
class LocalLinkCacheTest {

    private static final String KEY = "short-link:link:localhost:8080:abc123";

    private ShortLinkProperties properties;
    private LocalLinkCache localLinkCache;

    @BeforeEach
    void setUp() {
        properties = new ShortLinkProperties();
        properties.setDomain("localhost:8080");
        properties.getCache().getLocal().setMaxSize(100);
        properties.getCache().getLocal().setTtlSeconds(60);
        localLinkCache = new LocalLinkCache(properties);
    }

    private LinkCacheDTO dto() {
        LinkCacheDTO dto = new LinkCacheDTO();
        dto.setOriginalUrl("https://example.com/target");
        dto.setEnableStatus(0);
        dto.setValidType(1);
        return dto;
    }

    @Test
    void putThenGetReturnsSameValue() {
        localLinkCache.put(KEY, dto());

        LinkCacheDTO cached = localLinkCache.get(KEY);

        assertNotNull(cached);
        assertEquals("https://example.com/target", cached.getOriginalUrl());
        assertEquals(1, localLinkCache.estimatedSize());
    }

    @Test
    void getMissReturnsNull() {
        assertNull(localLinkCache.get(KEY));
    }

    @Test
    void invalidateRemovesEntry() {
        localLinkCache.put(KEY, dto());

        localLinkCache.invalidate(KEY);

        assertNull(localLinkCache.get(KEY));
    }

    @Test
    void invalidateAllClearsEntries() {
        localLinkCache.put(KEY, dto());
        localLinkCache.put(KEY + "2", dto());

        localLinkCache.invalidateAll();

        assertEquals(0, localLinkCache.estimatedSize());
    }

    @Test
    void disabledCacheIgnoresPutAndGet() {
        properties.getCache().getLocal().setEnabled(false);

        localLinkCache.put(KEY, dto());

        assertNull(localLinkCache.get(KEY));
        assertEquals(0, localLinkCache.estimatedSize());
    }

    @Test
    void statsRecordHitsAndMisses() {
        localLinkCache.get(KEY);
        localLinkCache.put(KEY, dto());
        localLinkCache.get(KEY);

        assertEquals(1, localLinkCache.stats().hitCount());
        assertEquals(1, localLinkCache.stats().missCount());
        assertTrue(localLinkCache.stats().hitRate() > 0);
    }
}
