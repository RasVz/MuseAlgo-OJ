package top.everblue.oj.judge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/** Submission aggregate owned by the judge module. */
@Getter
@Setter
@TableName("submission")
@ApiModel(value = "Submission对象", description = "评测记录表")
public class Submission implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("contest_id")
    private Long contestId;
    @TableField("vp_attempt_id")
    private Long vpAttemptId;
    @TableField("problem_id")
    private Long problemId;
    @TableField("user_id")
    private Long userId;
    /**
     * 提交源码（V50 起拆表至 submission_code，1:1 懒加载）。
     * <p>
     * 本字段非主表列（exist=false，不参与 SQL 生成/写入）；
     * 需要代码的路径（判题/回放/评阅/详情）须显式从
     * {@code submission_code} 加载后 setCode 填充。
     */
    @TableField(exist = false)
    private String code;
    @TableField("language")
    private String language;
    @TableField("score")
    private Integer score;
    @ApiModelProperty("0-待判, 1-AC, 2-WA, 3-CE, 4-RE, 5-TLE")
    @TableField("status")
    private Byte status;
    @TableField("judge_phase")
    private String judgePhase;
    @TableField("run_time")
    private Integer runTime;
    @TableField("run_memory")
    private Integer runMemory;
    @TableField("error_message")
    private String errorMessage;
    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
