package com.musealgo.oj.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理员维护的 AI 服务配置。
 *
 * <p>密文只允许在本模块内解密使用；{@link JsonIgnore} 是防误序列化的最后一道保险，
 * 管理接口仍只返回独立 VO，不能直接把实体交给 MVC 序列化。</p>
 */
@Getter
@Setter
@TableName("ai_service_config")
public class AiServiceConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String PROTOCOL_CHAT_COMPLETIONS = "CHAT_COMPLETIONS";
    public static final String TEST_PASSED = "PASSED";
    public static final String TEST_FAILED = "FAILED";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("name")
    private String name;

    @TableField("protocol")
    private String protocol;

    @TableField("api_url")
    private String apiUrl;

    @TableField("model")
    private String model;

    @JsonIgnore
    @TableField("api_key_encrypted")
    private String apiKeyEncrypted;

    @TableField("encryption_version")
    private String encryptionVersion;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("json_mode")
    private Boolean jsonMode;

    @TableField("stream_include_usage")
    private Boolean streamIncludeUsage;

    @TableField("max_tokens")
    private Integer maxTokens;

    @TableField(value = "remark", updateStrategy = FieldStrategy.ALWAYS)
    private String remark;

    @TableField("version")
    private Integer version;

    @TableField("last_test_version")
    private Integer lastTestVersion;

    @TableField("last_test_at")
    private LocalDateTime lastTestAt;

    @TableField("last_test_status")
    private String lastTestStatus;

    @TableField("is_deleted")
    private Boolean deleted;

    @TableField("created_by")
    private Long createdBy;

    @TableField("updated_by")
    private Long updatedBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /** 当前版本是否已有通过测试；旧版本测试不算。 */
    public boolean hasPassingTestForCurrentVersion() {
        return Boolean.TRUE.equals(enabled)
                && !Boolean.TRUE.equals(deleted)
                && TEST_PASSED.equals(lastTestStatus)
                && lastTestVersion != null
                && lastTestVersion.equals(version);
    }
}
