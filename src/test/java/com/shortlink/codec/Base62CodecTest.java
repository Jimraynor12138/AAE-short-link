package com.shortlink.codec;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Base62 编解码单元测试：已知值、随机往返、边界与非法输入
 */
class Base62CodecTest {

    @Test
    void encodeKnownValues() {
        assertEquals("1", Base62Codec.encode(1));
        assertEquals("Z", Base62Codec.encode(61));   // 字符表 0-9=0~9, a-z=10~35, A-Z=36~61
        assertEquals("10", Base62Codec.encode(62));
        assertEquals("100", Base62Codec.encode(62 * 62));
    }

    @Test
    void encodeIllegalId() {
        assertThrows(IllegalArgumentException.class, () -> Base62Codec.encode(0));
        assertThrows(IllegalArgumentException.class, () -> Base62Codec.encode(-1));
    }

    @Test
    void randomRoundTrip() {
        Random random = new Random(42);
        for (int i = 0; i < 10000; i++) {
            long id = 1L + Math.abs(random.nextLong() % Long.MAX_VALUE);
            String code = Base62Codec.encode(id);
            assertEquals(id, Base62Codec.decode(code), "编解码往返不一致, id=" + id);
        }
    }

    @Test
    void encodeUniqueness() {
        Set<String> codes = new HashSet<>();
        for (long id = 1; id <= 100000; id++) {
            codes.add(Base62Codec.encode(id));
        }
        assertEquals(100000, codes.size());
    }

    @Test
    void codeLengthBound() {
        String maxCode = Base62Codec.encode(Long.MAX_VALUE);
        assertTrue(maxCode.length() <= 11, "long 最大值编码长度应 <= 11, 实际: " + maxCode.length());
    }

    @Test
    void decodeIllegalInput() {
        assertThrows(IllegalArgumentException.class, () -> Base62Codec.decode(null));
        assertThrows(IllegalArgumentException.class, () -> Base62Codec.decode(""));
        assertThrows(IllegalArgumentException.class, () -> Base62Codec.decode("abc-1"));
    }

    @Test
    void decodeDoesNotThrowOnValidCode() {
        assertDoesNotThrow(() -> Base62Codec.decode("0123abcXYZ"));
    }
}
