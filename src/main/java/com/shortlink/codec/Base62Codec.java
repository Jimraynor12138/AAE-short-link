package com.shortlink.codec;

/**
 * Base62 编解码器：发号器产出的 long 型 ID 与短码的统一转换层。
 *
 * <p>字符表：0-9 a-z A-Z，共 62 个字符。
 * 6 位短码容量 62^6 ≈ 568 亿，10 位即可覆盖 long 正数全量。
 *
 * <p>设计要点：无论底层发号器是自增、Redis 还是 Snowflake，
 * 短码生成都收敛到这一个类，保证编码规则可独立演进（如未来加混淆位）。
 */
public final class Base62Codec {

    public static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    public static final int BASE = ALPHABET.length();

    private Base62Codec() {
    }

    /**
     * 将正整数编码为 Base62 字符串
     *
     * @param id 必须 > 0
     */
    public static String encode(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("id 必须为正数: " + id);
        }
        StringBuilder sb = new StringBuilder();
        while (id > 0) {
            sb.append(ALPHABET.charAt((int) (id % BASE)));
            id /= BASE;
        }
        return sb.reverse().toString();
    }

    /**
     * 将 Base62 字符串解码为 long
     *
     * @throws IllegalArgumentException 含非法字符或溢出时抛出
     */
    public static long decode(String code) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("短码不能为空");
        }
        long result = 0;
        for (int i = 0; i < code.length(); i++) {
            int digit = ALPHABET.indexOf(code.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("非法短码字符: " + code.charAt(i));
            }
            result = result * BASE + digit;
            if (result < 0) {
                throw new IllegalArgumentException("短码解码溢出: " + code);
            }
        }
        return result;
    }
}
