package com.musealgo.oj.ai.adminai;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/** 管理中心 AI 服务的输入/输出契约；任何响应都不包含密文或明文 Key。 */
public final class AiServiceAdminDtos {

    private AiServiceAdminDtos() {
    }

    @Getter
    @Setter
    public static class ServiceFieldsRequest {

        @NotBlank(message = "服务名称不能为空")
        @Size(max = 50, message = "服务名称不能超过 50 个字符")
        private String name;

        @Size(max = 32, message = "协议标识不能超过 32 个字符")
        private String protocol;

        @NotBlank(message = "接口地址不能为空")
        @Size(max = 512, message = "接口地址不能超过 512 个字符")
        private String apiUrl;

        @NotBlank(message = "模型名称不能为空")
        @Size(max = 128, message = "模型名称不能超过 128 个字符")
        private String model;

        /** 只在反序列化时接收；省略（provider=false）表示编辑时保留旧 Key。 */
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Size(max = 4096, message = "API 密钥过长")
        private String apiKey;

        @JsonIgnore
        private boolean apiKeyProvided;

        @Size(max = 500, message = "备注不能超过 500 个字符")
        private String remark;

        private Boolean jsonMode;
        private Boolean streamIncludeUsage;
        private Integer maxTokens;

        @JsonProperty("apiKey")
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
            this.apiKeyProvided = true;
        }
    }

    @Getter
    @Setter
    public static class CreateRequest extends ServiceFieldsRequest {
    }

    @Getter
    @Setter
    public static class UpdateRequest extends ServiceFieldsRequest {

        @NotNull(message = "缺少配置版本号")
        @Min(value = 1, message = "配置版本号非法")
        private Integer version;

        /** 编辑当前服务时必须提供，用于确认管理员操作的是同一个运行时视图。 */
        @Min(value = 1, message = "运行时版本号非法")
        private Integer runtimeVersion;
    }

    public record VersionRequest(
            @NotNull(message = "缺少配置版本号")
            @Min(value = 1, message = "配置版本号非法") Integer version) {
    }

    public record ActivateRequest(
            @NotNull(message = "缺少配置版本号")
            @Min(value = 1, message = "配置版本号非法") Integer version,
            @NotNull(message = "缺少运行时版本号")
            @Min(value = 1, message = "运行时版本号非法") Integer runtimeVersion) {
    }

    public record RuntimeUpdateRequest(
            @NotBlank(message = "运行模式不能为空") String mode,
            @NotNull(message = "缺少运行时版本号")
            @Min(value = 1, message = "运行时版本号非法") Integer version) {
    }

    public record ServiceVo(
            Long id,
            String name,
            String protocol,
            String apiUrl,
            String model,
            boolean hasApiKey,
            boolean enabled,
            String usageStatus,
            boolean jsonMode,
            boolean streamIncludeUsage,
            int maxTokens,
            String remark,
            int version,
            Integer lastTestVersion,
            LocalDateTime lastTestAt,
            String lastTestStatus,
            String testState,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record UpdateResponse(
            ServiceVo service,
            TestReport testReport,
            boolean applied) {
    }

    public record RuntimeVo(
            String mode,
            Long currentServiceId,
            String currentServiceName,
            String currentServiceModel,
            Integer currentServiceVersion,
            boolean currentServiceReady,
            int runtimeVersion,
            String legacyModel,
            com.musealgo.oj.ai.crypto.AiKeyCipher.Readiness masterKey) {
    }

    public record TestCheck(
            String kind,
            boolean success,
            boolean skipped,
            long latencyMs,
            String errorType,
            String message) {
    }

    public record TestReport(
            Long serviceId,
            int configVersion,
            boolean passed,
            LocalDateTime testedAt,
            long totalLatencyMs,
            List<TestCheck> checks) {
    }
}
