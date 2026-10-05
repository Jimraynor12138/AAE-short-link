package com.shortlink.bloom;

import cn.hutool.crypto.digest.DigestUtil;

import java.nio.charset.StandardCharsets;

/**
 * 布隆过滤器哈希计算（纯函数，便于单测与复用）。
 *
 * 采用「双重哈希」构造 k 个位置：offset_i = (h1 + i * h2) mod m
 * 只需要一次 MD5 摘要即可派生 h1/h2，避免计算 k 次独立哈希。
 */
public final class BloomFilterHash {

    private BloomFilterHash() {
    }

    /**
     * 计算某个值在位数组中的所有下标
     *
     * @param value     待哈希的短码
     * @param bitSize   位数组长度 m
     * @param hashCount 哈希次数 k
     * @return 长度为 hashCount 的下标数组
     */
    public static long[] offsets(String value, long bitSize, int hashCount) {
        byte[] digest = DigestUtil.md5(value.getBytes(StandardCharsets.UTF_8));
        long h1 = readLong(digest, 0);
        // h2 取奇数：避免与 2 的幂相关的位数组长度出现分布退化
        long h2 = readLong(digest, 8) | 1L;

        long[] offsets = new long[hashCount];
        for (int i = 0; i < hashCount; i++) {
            // Math.floorMod 保证结果非负（h2 可能为负）
            offsets[i] = Math.floorMod(h1 + i * h2, bitSize);
        }
        return offsets;
    }

    /**
     * 依据容量 n 与目标误判率 p 计算位数组长度：m = -n * ln(p) / (ln2)^2
     */
    public static long bitSize(long capacity, double falsePositiveRate) {
        double m = -capacity * Math.log(falsePositiveRate) / (Math.log(2) * Math.log(2));
        return Math.max(64L, (long) Math.ceil(m));
    }

    /**
     * 依据 m 与 n 计算哈希次数：k = (m / n) * ln2
     */
    public static int hashCount(long bitSize, long capacity) {
        int k = (int) Math.round((double) bitSize / capacity * Math.log(2));
        return Math.max(1, k);
    }

    private static long readLong(byte[] bytes, int offset) {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xFFL);
        }
        return value;
    }
}
