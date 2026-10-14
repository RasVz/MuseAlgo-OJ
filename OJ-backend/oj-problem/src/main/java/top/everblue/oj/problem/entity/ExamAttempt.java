package top.everblue.oj.problem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 训练试卷作答会话（限时）。 */
@Getter
@Setter
@TableName("exam_attempt")
public class ExamAttempt implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("paper_id")
    private Long paperId;

    @TableField("user_id")
    private Long userId;

    private String status;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("end_time")
    private LocalDateTime endTime;

    @TableField("submit_time")
    private LocalDateTime submitTime;

    @TableField("objective_score")
    private Integer objectiveScore;

    @TableField("coding_score")
    private Integer codingScore;

    @TableField("total_score")
    private Integer totalScore;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
