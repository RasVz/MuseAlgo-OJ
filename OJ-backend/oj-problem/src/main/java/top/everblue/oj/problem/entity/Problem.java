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
 * <p>
 * 题目表
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
@Getter
@Setter
@TableName("problem")
@ApiModel(value = "Problem对象", description = "题目表")
public class Problem implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("题目ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty("题目标题")
    @TableField("title")
    private String title;

    // === 新增字段 ===
    @ApiModelProperty("关联的比赛ID")
    @TableField("contest_id")
    private Long contestId;
    // ===============

    @ApiModelProperty("题目内容(Markdown)")
    @TableField("content")
    private String content;

    @ApiModelProperty("输入描述")
    @TableField("input_desc")
    private String inputDesc;

    @ApiModelProperty("输出描述")
    @TableField("output_desc")
    private String outputDesc;

    // === 新增字段 ===
    @ApiModelProperty("题目提示(Markdown)")
    @TableField("hint")
    private String hint;
    // ===============

    @ApiModelProperty("AI 生成的标准答案(C++ 代码，仅教师可见)")
    @TableField("std_answer")
    private String stdAnswer;

    @ApiModelProperty("标准答案算法思路讲解(仅教师可见)")
    @TableField("std_explanation")
    private String stdExplanation;

    @ApiModelProperty("标准答案是否已通过测试数据验证（0-未测试，1-已测试）")
    @TableField("std_tested")
    private Integer stdTested;

    @ApiModelProperty("时间限制(ms)")
    @TableField("time_limit")
    private Integer timeLimit;

    @ApiModelProperty("内存限制(MB)")
    @TableField("memory_limit")
    private Integer memoryLimit;

    @ApiModelProperty("难度分数 800-3000")
    @TableField("difficulty")
    private Integer difficulty;

    @ApiModelProperty("标签JSON: [\"DP\",\"Array\"]")
    @TableField("tags")
    private String tags;

    // === 新增统计与来源字段 ===
    @ApiModelProperty("通过次数")
    @TableField("pass_count")
    private Integer passCount;

    @ApiModelProperty("提交总数")
    @TableField("total_count")
    private Integer totalCount;

    @ApiModelProperty("测试用例数量")
    @TableField("test_case_count")
    private Integer testCaseCount;

    @ApiModelProperty("出题人ID")
    @TableField("author_id")
    private Long authorId;

    @ApiModelProperty("题目来源")
    @TableField("source")
    private String source;
    // ========================

    @ApiModelProperty("0-私有, 1-公开")
    @TableField("is_public")
    private Boolean isPublic;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("is_deleted")
    private Integer isDeleted;
}
