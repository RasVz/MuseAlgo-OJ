package top.everblue.oj.problem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 试卷客观题（单选 / 多选 / 判断，提交时判分）。 */
@Getter
@Setter
@TableName("exam_question")
public class ExamQuestion implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String TYPE_SINGLE = "SINGLE";
    public static final String TYPE_MULTIPLE = "MULTIPLE";
    public static final String TYPE_JUDGE = "JUDGE";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("paper_id")
    private Long paperId;

    @TableField("question_type")
    private String questionType;

    private String statement;

    /** JSON 数组字符串：[{"key":"A","text":"..."}]；判断题可为空 */
    private String options;

    private String answer;

    /** 解析（判分后展示给学生） */
    private String explanation;

    private Integer score;

    @TableField("question_order")
    private Integer questionOrder;

    @TableField("create_time")
    private LocalDateTime createTime;
}
