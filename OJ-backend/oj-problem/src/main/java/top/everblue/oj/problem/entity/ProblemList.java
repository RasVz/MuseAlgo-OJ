package top.everblue.oj.problem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * 题单表
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
@Getter
@Setter
@TableName("problem_list")
@ApiModel(value = "ProblemList对象", description = "题单表")
public class ProblemList implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty("标题")
    @TableField("title")
    private String title;

    @ApiModelProperty("创建人ID")
    @TableField("creator_id")
    private Long creatorId;

    @ApiModelProperty("简介")
    @TableField("description")
    private String description;

    // 修正注释，与 SQL 保持一致
    @ApiModelProperty("类型: 1-个人题单, 2-官方题单, 3-班级题单, 4-班级作业")
    @TableField("type")
    private Byte type;

    @ApiModelProperty("班级ID (仅 type=3/4 有效)")
    @TableField("class_id")
    private Long classId;

    @ApiModelProperty("0-私有, 1-公开")
    @TableField("privacy")
    private Integer privacy;

    @ApiModelProperty("开始时间")
    @TableField("start_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime startTime;

    @ApiModelProperty("截止时间")
    @TableField("end_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime endTime;

    @ApiModelProperty("题目数缓存")
    @TableField("problem_count")
    private Integer problemCount;

    @ApiModelProperty("收藏/引用次数")
    @TableField("favorite_count")
    private Integer favoriteCount;

    @ApiModelProperty("手动排序值（越小越靠前，0=默认按创建时间）")
    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("create_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    @TableField("update_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;
}
