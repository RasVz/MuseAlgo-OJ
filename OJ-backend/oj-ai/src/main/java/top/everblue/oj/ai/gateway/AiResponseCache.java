package top.everblue.oj.ai.gateway;

import cn.hutool.json.JSONArray;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 响应内存缓存（O5）。
 * <p>
 * 同类高频问题（如题目翻译/格式化、FAQ）命中率很高；缓存命中直接省一次模型调用。
 * 约束：TTL 10 分钟、最多 512 条，超出时整体清空（简单可回退，避免内存泄漏）；
 * 只缓存普通对话（json 模式不缓存，保证结构化输出时效性）。
 * </p>
 */
@Component
public class AiResponseCache {

    private static final long TTL_MILLIS = 10 * 60 * 1000L;
    private static final int MAX_ENTRIES = 512;

    private record Entry(String value, long expireAt) {
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public String get(JSONArray messages) {
        Entry entry = cache.get(key(messages));
        if (entry == null) {
            return null;
        }
        if (entry.expireAt() < System.currentTimeMillis()) {
            cache.remove(key(messages), entry);
            return null;
        }
        return entry.value();
    }

    public void put(JSONArray messages, String answer) {
        if (answer == null || answer.isBlank()) {
            return;
        }
        if (cache.size() >= MAX_ENTRIES) {
            // 简单退化策略：全量清空，保证缓存永不成为内存压力
            cache.clear();
        }
        cache.put(key(messages), new Entry(answer, System.currentTimeMillis() + TTL_MILLIS));
    }

    private static String key(JSONArray messages) {
        String json = messages.toString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(json.hashCode());
        }
    }
}
