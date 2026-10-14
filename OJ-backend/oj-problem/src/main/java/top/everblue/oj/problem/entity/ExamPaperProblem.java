package top.everblue.oj.problem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 试卷-题目关联。 */
@Getter
@Setter
@TableName("exam_paper_problem")
public class ExamPaperProblem implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("paper_id")
    private Long paperId;

    @TableField("problem_id")
    private Long problemId;

    @TableField("problem_order")
    private Integer problemOrder;

    private Integer score;

    @TableField("create_time")
    private LocalDateTime createTime;
}
