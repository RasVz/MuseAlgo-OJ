package top.everblue.oj.problem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * 题单题目关联
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
@Getter
@Setter
@TableName("problem_list_item")
@ApiModel(value = "ProblemListItem对象", description = "题单题目关联")
public class ProblemListItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty("题单ID")
    @TableField("list_id")
    private Long listId;

    @ApiModelProperty("题目ID")
    @TableField("problem_id")
    private Long problemId;

    @ApiModelProperty("排序(越小越前)")
    @TableField("sort")
    private Integer sort;
}
