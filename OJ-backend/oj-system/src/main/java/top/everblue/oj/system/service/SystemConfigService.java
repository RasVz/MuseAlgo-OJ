package top.everblue.oj.system.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.everblue.oj.system.mapper.SystemConfigMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统配置服务：DB 覆盖 yml 默认值，带 5 秒内存缓存。
 * 写操作立即失效缓存，保证管理台改动秒级生效。
 */
@Service
@RequiredArgsConstructor
public class SystemConfigService {

    private static final long CACHE_TTL_MS = 5_000L;

    private final SystemConfigMapper systemConfigMapper;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public String get(String key, String defaultValue) {
        Cached cached = cache.get(key);
        if (cached != null && cached.expireAt > System.currentTimeMillis()) {
            return cached.value != null ? cached.value : defaultValue;
        }
        String value = systemConfigMapper.selectById(key) != null
                ? systemConfigMapper.selectById(key).getConfigValue() : null;
        cache.put(key, new Cached(value, System.currentTimeMillis() + CACHE_TTL_MS));
        return value != null ? value : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        String value = get(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBool(String key, boolean defaultValue) {
        String value = get(key, null);
        if (value == null) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value.trim())
                || "1".equals(value.trim())
                || "on".equalsIgnoreCase(value.trim());
    }

    public void set(String key, String value) {
        if (value == null) {
            value = "";
        }
        systemConfigMapper.upsert(key, value);
        cache.put(key, new Cached(value, System.currentTimeMillis() + CACHE_TTL_MS));
    }

    private record Cached(String value, long expireAt) {
    }
}
