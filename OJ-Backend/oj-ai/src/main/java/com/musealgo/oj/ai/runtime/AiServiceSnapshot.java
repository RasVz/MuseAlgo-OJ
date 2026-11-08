package com.musealgo.oj.ai.runtime;

import com.musealgo.oj.ai.dto.AiCallSource;

import java.net.URI;
import java.util.Objects;

/**
 * 单次模型调用的完整不可变配置快照。
 *
 * <p>密钥只存在于本对象内，{@link #toString()} 不含密钥、URL 或密文。
 * 调用方一次请求只使用一个快照，避免切换后混用两个服务的参数。</p>
 */
public final class AiServiceSnapshot {

    public static final String PROTOCOL_CHAT_COMPLETIONS = "CHAT_COMPLETIONS";

    private final String mode;
    private final Long serviceId;
    private final String serviceName;
    private final String protocol;
    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final Integer configVersion;
    private final boolean jsonMode;
    private final boolean streamIncludeUsage;
    private final int maxTokens;
    private final boolean available;
    private final String unavailableType;
    private final String unavailableMessage;

    private AiServiceSnapshot(String mode, Long serviceId, String serviceName, String protocol,
                              URI endpoint, String model, String apiKey, Integer configVersion,
                              boolean jsonMode, boolean streamIncludeUsage, int maxTokens,
                              boolean available, String unavailableType, String unavailableMessage) {
        this.mode = mode;
        this.serviceId = serviceId;
        this.serviceName = serviceName;
        this.protocol = protocol;
        this.endpoint = endpoint;
        this.model = model;
        this.apiKey = apiKey;
        this.configVersion = configVersion;
        this.jsonMode = jsonMode;
        this.streamIncludeUsage = streamIncludeUsage;
        this.maxTokens = maxTokens;
        this.available = available;
        this.unavailableType = unavailableType;
        this.unavailableMessage = unavailableMessage;
    }

    public static AiServiceSnapshot managed(Long id, String name, String protocol, URI endpoint,
                                            String model, String apiKey, int version,
                                            boolean jsonMode, boolean includeUsage, int maxTokens) {
        return new AiServiceSnapshot("MANAGED", id, name, protocol, endpoint, model, apiKey, version,
                jsonMode, includeUsage, maxTokens, true, null, null);
    }

    public static AiServiceSnapshot legacy(URI endpoint, String model, String apiKey,
                                           boolean jsonMode, int maxTokens) {
        boolean configured = apiKey != null && !apiKey.isBlank();
        return new AiServiceSnapshot("LEGACY", null, "旧环境配置", PROTOCOL_CHAT_COMPLETIONS,
                endpoint, model, apiKey, null, jsonMode, true, maxTokens,
                configured, configured ? null : "NOT_CONFIGURED",
                configured ? null : "旧环境 AI Key 未配置");
    }

    public static AiServiceSnapshot off() {
        return new AiServiceSnapshot("OFF", null, "AI 已停用", PROTOCOL_CHAT_COMPLETIONS,
                null, "unavailable", null, null, false, false, 0,
                false, "AI_DISABLED", "AI 服务已停用");
    }

    public static AiServiceSnapshot unavailableManaged(Long id, String name, String protocol, String model,
                                                       int version, String type, String message) {
        return new AiServiceSnapshot("MANAGED", id, name, protocol, null, model, null, version,
                false, false, 0, false, type, message);
    }

    public static AiServiceSnapshot unavailableLegacy(String model, String type, String message) {
        return new AiServiceSnapshot("LEGACY", null, "旧环境配置", PROTOCOL_CHAT_COMPLETIONS,
                null, model, null, null, false, true, 0, false, type, message);
    }

    public AiCallSource source() {
        return "OFF".equals(mode)
                ? AiCallSource.off()
                : new AiCallSource(serviceId, serviceName, model, configVersion, mode);
    }

    public String mode() {
        return mode;
    }

    public Long serviceId() {
        return serviceId;
    }

    public String serviceName() {
        return serviceName;
    }

    public String protocol() {
        return protocol;
    }

    public URI endpoint() {
        return endpoint;
    }

    public String model() {
        return model;
    }

    public String apiKey() {
        return apiKey;
    }

    public Integer configVersion() {
        return configVersion;
    }

    public boolean jsonMode() {
        return jsonMode;
    }

    public boolean streamIncludeUsage() {
        return streamIncludeUsage;
    }

    public int maxTokens() {
        return maxTokens;
    }

    public boolean available() {
        return available;
    }

    public String unavailableType() {
        return unavailableType;
    }

    public String unavailableMessage() {
        return unavailableMessage;
    }

    public boolean supportsChatCompletions() {
        return PROTOCOL_CHAT_COMPLETIONS.equals(protocol);
    }

    /**
     * 缓存隔离材料：运行模式、服务、版本、模型和生成参数全部参与。
     * 不含密钥；配置版本变化会天然失效。
     */
    public String cacheScope() {
        return mode + "|" + Objects.toString(serviceId, "-") + "|" + Objects.toString(configVersion, "-")
                + "|" + model + "|" + (jsonMode ? "json" : "text")
                + "|usage=" + streamIncludeUsage + "|max=" + maxTokens;
    }

    @Override
    public String toString() {
        return "AiServiceSnapshot{mode=" + mode
                + ", serviceId=" + serviceId
                + ", model=" + model
                + ", version=" + configVersion
                + ", available=" + available
                + '}';
    }
}
