package top.everblue.oj.problem.entity;

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

/**
 * 题目公开样例表：与判题用例（problem_case）分离，一道题可有多组样例。
 */
@Getter
@Setter
@TableName("problem_sample")
@ApiModel(value = "ProblemSample对象", description = "题目公开样例表")
public class ProblemSample implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty("题目ID")
    @TableField("problem_id")
    private Long problemId;

    @ApiModelProperty("样例顺序，从 1 开始")
    @TableField("sample_order")
    private Integer sampleOrder;

    @ApiModelProperty("样例输入")
    @TableField("input")
    private String input;

    @ApiModelProperty("样例输出")
    @TableField("output")
    private String output;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
