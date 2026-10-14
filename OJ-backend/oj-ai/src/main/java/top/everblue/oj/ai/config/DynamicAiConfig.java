package top.everblue.oj.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.everblue.oj.api.system.SystemConfigQuery;

/**
 * AI 动态配置（E1）：DB（system_config）覆盖 yml 默认值。
 * 管理台改配置秒级生效；未配置的键回落到 application.yml。
 * <p>
 * 配置读取走 oj-api 契约 {@link SystemConfigQuery}，不直接依赖 oj-system。
 * </p>
 */
@Component
public class DynamicAiConfig {

    private final SystemConfigQuery config;

    @Value("${oj.ai.quota.enabled:true}")
    private boolean fallbackQuotaEnabled;
    @Value("${oj.ai.quota.daily-tokens:30000}")
    private long fallbackDailyTokens;
    @Value("${oj.ai.quota.exempt-teacher-admin:true}")
    private boolean fallbackExemptTeacherAdmin;
    @Value("${oj.ai.contest-guard.enabled:true}")
    private boolean fallbackContestGuardEnabled;
    @Value("${oj.ai.contest-guard.cache-seconds:60}")
    private long fallbackContestGuardCacheSeconds;
    @Value("${ai.model:deepseek-chat}")
    private String fallbackModel;
    @Value("${ai.max-tokens:8192}")
    private int fallbackMaxTokens;
    @Value("${ai.json-mode:true}")
    private boolean fallbackJsonMode;

    public DynamicAiConfig(SystemConfigQuery config) {
        this.config = config;
    }

    public boolean quotaEnabled() {
        return config.getBool("ai.quota.enabled", fallbackQuotaEnabled);
    }

    public long dailyTokens() {
        return config.getInt("ai.quota.daily-tokens", (int) fallbackDailyTokens);
    }

    public boolean exemptTeacherAdmin() {
        return config.getBool("ai.quota.exempt-teacher-admin", fallbackExemptTeacherAdmin);
    }

    public boolean contestGuardEnabled() {
        return config.getBool("ai.contest-guard.enabled", fallbackContestGuardEnabled);
    }

    public long contestGuardCacheSeconds() {
        return config.getInt("ai.contest-guard.cache-seconds", (int) fallbackContestGuardCacheSeconds);
    }

    public String model() {
        return config.get("ai.model", fallbackModel);
    }

    public int maxTokens() {
        return config.getInt("ai.max-tokens", fallbackMaxTokens);
    }

    public boolean jsonMode() {
        return config.getBool("ai.json-mode", fallbackJsonMode);
    }
}
