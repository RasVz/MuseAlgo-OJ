package com.musealgo.oj.ai.dto;

/**
 * 一次模型调用的非敏感来源快照，供用量与缓存使用。
 * 不含 URL、密钥或供应商原始响应。
 */
public record AiCallSource(
        Long serviceId,
        String serviceName,
        String model,
        Integer configVersion,
        String runtimeMode) {

    public static AiCallSource legacy(String model) {
        return new AiCallSource(null, "旧环境配置", model, null, "LEGACY");
    }

    public static AiCallSource off() {
        return new AiCallSource(null, "AI 已停用", "unavailable", null, "OFF");
    }

    public static AiCallSource unavailable() {
        return new AiCallSource(null, "运行配置不可用", "unavailable", null, "UNKNOWN");
    }
}
