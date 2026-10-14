package top.everblue.oj.analytics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 代码查重报告——班级内同题两两比对结果（M1）。
 * <p>
 * 幂等：{@code uk_pair(class_id, problem_id, submission_a_id, submission_b_id)}
 * 唯一键保证同一对提交不重复入库；{@code user_a_id < user_b_id} 规范化去重对。
 * </p>
 */
@Getter
@Setter
@TableName("plagiarism_report")
public class PlagiarismReport implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("class_id")
    private Long classId;

    @TableField("contest_id")
    private Long contestId;

    @TableField("problem_id")
    private Long problemId;

    @TableField("submission_a_id")
    private Long submissionAId;

    @TableField("submission_b_id")
    private Long submissionBId;

    @TableField("user_a_id")
    private Long userAId;

    @TableField("user_b_id")
    private Long userBId;

    @TableField("similarity")
    private Double similarity;

    /** 指纹方法：AST / TOKEN */
    @TableField("method")
    private String method;

    /** PENDING / REVIEWED / IGNORED */
    @TableField("status")
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
