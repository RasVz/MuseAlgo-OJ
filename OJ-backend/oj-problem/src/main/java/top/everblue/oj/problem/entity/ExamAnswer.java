package top.everblue.oj.problem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 客观题作答与判分记录。 */
@Getter
@Setter
@TableName("exam_answer")
public class ExamAnswer implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("paper_id")
    private Long paperId;

    @TableField("attempt_id")
    private Long attemptId;

    @TableField("question_id")
    private Long questionId;

    @TableField("user_id")
    private Long userId;

    private String answer;

    @TableField("is_correct")
    private Integer isCorrect;

    private Integer score;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
