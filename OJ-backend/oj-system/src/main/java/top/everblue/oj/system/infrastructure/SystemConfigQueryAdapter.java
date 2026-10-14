package top.everblue.oj.system.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.system.SystemConfigQuery;
import top.everblue.oj.system.service.SystemConfigService;

/**
 * 系统配置契约适配器 — oj-system 侧实现 {@link SystemConfigQuery}。
 */
@Service
@RequiredArgsConstructor
public class SystemConfigQueryAdapter implements SystemConfigQuery {

    private final SystemConfigService systemConfigService;

    @Override
    public String get(String key, String defaultValue) {
        return systemConfigService.get(key, defaultValue);
    }

    @Override
    public int getInt(String key, int defaultValue) {
        return systemConfigService.getInt(key, defaultValue);
    }

    @Override
    public boolean getBool(String key, boolean defaultValue) {
        return systemConfigService.getBool(key, defaultValue);
    }
}
