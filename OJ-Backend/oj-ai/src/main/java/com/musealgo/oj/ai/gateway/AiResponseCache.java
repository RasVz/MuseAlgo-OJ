package com.musealgo.oj.ai.gateway;

import cn.hutool.json.JSONArray;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 响应内存缓存。
 *
 * <p>键包含运行模式、服务 ID、配置版本、模型、生成参数与消息。切换服务或编辑当前配置后
 * 配置版本变化，旧回答不会被新服务命中。只缓存普通文本，JSON 模式不缓存。</p>
 */
@Component
public class AiResponseCache {

    private static final long TTL_MILLIS = 10 * 60 * 1000L;
    private static final int MAX_ENTRIES = 512;

    private record Entry(String value, long expireAt) {
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public String get(String scope, JSONArray messages) {
        String key = key(scope, messages);
        Entry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expireAt() < System.currentTimeMillis()) {
            cache.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    public void put(String scope, JSONArray messages, String answer) {
        if (answer == null || answer.isBlank()) {
            return;
        }
        if (cache.size() >= MAX_ENTRIES) {
            // 简单退化策略：全量清空，保证缓存永不成为内存压力
            cache.clear();
        }
        cache.put(key(scope, messages), new Entry(answer, System.currentTimeMillis() + TTL_MILLIS));
    }

    static String key(String scope, JSONArray messages) {
        String material = (scope == null ? "-" : scope) + "\n" + messages;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(material.hashCode());
        }
    }
}
