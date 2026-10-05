package com.shortlink.bloom;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Redis 位图的二进制读写（V3.4 修复项）。
 *
 * 为什么单独抽一层：位图是**二进制数据**，必须走连接层（`GET`/`SET`/`GETBIT`/`SETBIT`）读写，
 * 不能用 `StringRedisTemplate` 的字符串 API 整块读取（会按 UTF-8 解码破坏字节内容）。
 * 把这层糙活隔离出来，上面的过滤器只处理"本地位数组 + 少量 Redis 调用"，便于单测。
 *
 * 性能要点（就是本次修复的核心）：
 * - 整块读/写位图：1 个 RTT（过去预热是"每个短码 k 次 SETBIT"，1 万个短码就是 7 万次 RTT）
 * - 批量读位：pipelined 合并成 1 个 RTT（用于"本地判定不存在"时回查确认）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BloomBitmapStore {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 整块读取位图
     *
     * @return 位图字节；key 不存在返回 null
     */
    public byte[] readBitmap(String key) {
        return stringRedisTemplate.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(bytes(key)));
    }

    /**
     * 整块写入位图（预热/重建）：1 次 RTT 覆盖全量短码
     */
    public void writeBitmap(String key, byte[] bitmap) {
        stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.stringCommands().set(bytes(key), bitmap);
            return null;
        });
    }

    /**
     * 批量读取若干位的值（pipelined：k 次 GETBIT 合并为 1 个 RTT）
     */
    public boolean[] readBits(String key, long[] offsets) {
        List<Object> results = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (long offset : offsets) {
                connection.stringCommands().getBit(bytes(key), offset);
            }
            return null;
        });
        boolean[] bits = new boolean[offsets.length];
        for (int i = 0; i < bits.length && i < results.size(); i++) {
            bits[i] = Boolean.TRUE.equals(results.get(i));
        }
        return bits;
    }

    /**
     * 批量置位（pipelined：1 个 RTT）
     */
    public void setBits(String key, long[] offsets) {
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (long offset : offsets) {
                connection.stringCommands().setBit(bytes(key), offset, true);
            }
            return null;
        });
    }

    /**
     * 整块置位（用于把"本地构建好的位图"与 Redis 对齐）
     */
    public void markInit(String initFlagKey) {
        stringRedisTemplate.opsForValue().set(initFlagKey, "1");
    }

    public boolean exists(String key) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    }

    private static byte[] bytes(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }
}
