package top.everblue.oj.problem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 判题用例表
 */
@Getter
@Setter
@TableName("problem_case")
@ApiModel(value = "ProblemCase对象", description = "判题用例表")
public class ProblemCase implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty("题目ID")
    @TableField("problem_id")
    private Long problemId;

    @ApiModelProperty("测试输入文件路径")
    @TableField("input_file_path")
    private String inputFilePath;

    @ApiModelProperty("期望输出文件路径")
    @TableField("output_file_path")
    private String outputFilePath;

    @ApiModelProperty("该测试点的分数")
    @TableField("score")
    private Integer score;

    @ApiModelProperty("测试点顺序，从 1 开始")
    @TableField("case_order")
    private Integer caseOrder;

    @ApiModelProperty("子任务编号，从 1 开始；同题同组为一组（OI 部分分用）")
    @TableField("subtask_no")
    private Integer subtaskNo;

    @ApiModelProperty("0-隐藏测试点, 1-公开样例")
    @TableField("case_type")
    private Byte caseType;

    @ApiModelProperty("0-禁用, 1-启用")
    @TableField("status")
    private Byte status;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
