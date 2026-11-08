package com.musealgo.oj.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 单行运行时选择：LEGACY / MANAGED / OFF。 */
@Getter
@Setter
@TableName("ai_runtime_config")
public class AiRuntimeConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final short SINGLETON_ID = 1;

    public static final String MODE_LEGACY = "LEGACY";
    public static final String MODE_MANAGED = "MANAGED";
    public static final String MODE_OFF = "OFF";

    @TableId(value = "id", type = IdType.INPUT)
    private Short id;

    @TableField("mode")
    private String mode;

    // 从 MANAGED 切到 OFF/LEGACY 时必须把 NULL 真正写进数据库；默认 NOT_NULL 会漏掉该列。
    @TableField(value = "current_service_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long currentServiceId;

    @TableField("version")
    private Integer version;

    @TableField("updated_by")
    private Long updatedBy;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
