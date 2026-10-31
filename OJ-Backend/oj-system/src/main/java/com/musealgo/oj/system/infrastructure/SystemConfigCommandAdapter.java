package com.musealgo.oj.system.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.musealgo.oj.api.system.SystemConfigCommand;
import com.musealgo.oj.system.service.SystemConfigService;

/**
 * 系统配置写契约适配器 — oj-system 侧实现 {@link SystemConfigCommand}。
 */
@Service
@RequiredArgsConstructor
public class SystemConfigCommandAdapter implements SystemConfigCommand {

    private final SystemConfigService systemConfigService;

    @Override
    public void set(String key, String value) {
        systemConfigService.set(key, value);
    }
}
