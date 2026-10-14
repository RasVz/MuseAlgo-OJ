package top.everblue.oj.analytics.entity;

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

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}