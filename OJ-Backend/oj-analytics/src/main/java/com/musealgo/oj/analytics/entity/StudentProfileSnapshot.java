package com.musealgo.oj.analytics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学生画像周快照——由定时任务每周一生成并落库。
 * <p>
 * {@code snapshot_json} 存储完整的画像数据（mastery、百分位、趋势等），
 * 外层服务直接读取此字段组装报告或 API 响应。
 */
@Getter
@Setter
@TableName("student_profile_snapshot")
public class StudentProfileSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("student_id")
    private Long studentId;

    @TableField("class_id")
    private Long classId;

    @TableField("period_start")
    private LocalDate periodStart;

    @TableField("period_end")
    private LocalDate periodEnd;

    @TableField("snapshot_json")
    private String snapshotJson;

    @TableField("report_url")
    private String reportUrl;

    /**
     * 派生数据过期标记（V56 迁移：TINYINT(1) NOT NULL DEFAULT 0）。
     * <p>
     * 重判改变历史提交结论后，该周期快照里的掌握度/百分位等派生值不再可信：
     * 由 {@code RejudgePostProcessListener} 置 1，报告读取上周基线时过滤 stale=1
     * （避免把过期快照当对比基线）；重新生成该周期快照（upsert）时复位为 0。
     * </p>
     */
    @TableField("stale")
    private Boolean stale;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}