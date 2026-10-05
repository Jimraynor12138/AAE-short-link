package com.shortlink.bloom;

import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 布隆过滤器哈希与参数推导测试：
 * 下标范围/确定性/参数公式，以及用内存位图实测「误判率确实接近目标值」
 */
class BloomFilterHashTest {

    @Test
    void offsetsAreDeterministicAndInRange() {
        long bitSize = 100_000L;
        int hashCount = 7;

        long[] first = BloomFilterHash.offsets("abc123", bitSize, hashCount);
        long[] second = BloomFilterHash.offsets("abc123", bitSize, hashCount);

        assertEquals(hashCount, first.length);
        for (int i = 0; i < hashCount; i++) {
            assertEquals(first[i], second[i], "同一短码必须得到相同下标");
            assertTrue(first[i] >= 0 && first[i] < bitSize, "下标必须落在位数组范围内: " + first[i]);
        }
    }

    @Test
    void differentCodesProduceDifferentOffsets() {
        long[] a = BloomFilterHash.offsets("abc123", 100_000L, 7);
        long[] b = BloomFilterHash.offsets("abc124", 100_000L, 7);
        int same = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == b[i]) {
                same++;
            }
        }
        assertTrue(same < a.length, "相邻短码不应产生完全相同的下标集合");
    }

    @Test
    void formulaGivesExpectedMagnitude() {
        // 100 万容量、1% 误判率：m ≈ 958 万位（约 1.2MB），k ≈ 7
        long bitSize = BloomFilterHash.bitSize(1_000_000L, 0.01d);
        int hashCount = BloomFilterHash.hashCount(bitSize, 1_000_000L);

        assertTrue(bitSize > 9_000_000L && bitSize < 10_000_000L, "位数组长度异常: " + bitSize);
        assertTrue(hashCount >= 6 && hashCount <= 8, "哈希次数异常: " + hashCount);
    }

    @Test
    void falsePositiveRateIsCloseToTarget() {
        // 用内存 BitSet 完整模拟一次布隆过滤器，实测误判率
        long capacity = 10_000L;
        double target = 0.01d;
        long bitSize = BloomFilterHash.bitSize(capacity, target);
        int hashCount = BloomFilterHash.hashCount(bitSize, capacity);
        BitSet bits = new BitSet();

        // 写入 capacity 个「真实存在」的短码
        for (int i = 0; i < capacity; i++) {
            for (long offset : BloomFilterHash.offsets("exist-" + i, bitSize, hashCount)) {
                bits.set((int) offset);
            }
        }

        // 查询 10000 个「不存在」的短码，统计被误判为存在的比例
        int falsePositive = 0;
        int queryCount = 10_000;
        for (int i = 0; i < queryCount; i++) {
            boolean mightContain = true;
            for (long offset : BloomFilterHash.offsets("absent-" + i, bitSize, hashCount)) {
                if (!bits.get((int) offset)) {
                    mightContain = false;
                    break;
                }
            }
            if (mightContain) {
                falsePositive++;
            }
        }

        double rate = (double) falsePositive / queryCount;
        // 理论值约 1%，给 3 倍容差避免随机性导致的偶发失败
        assertTrue(rate <= target * 3, "误判率偏高: " + rate);
    }
}
