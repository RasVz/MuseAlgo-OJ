package top.everblue.oj.teaching.entity;

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
 * 课件资源表
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
@Getter
@Setter
@TableName("courseware")
@ApiModel(value = "Courseware对象", description = "课件资源表")
public class Courseware implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty("创建老师ID")
    @TableField("teacher_id")
    private Long teacherId;

    @ApiModelProperty("课件标题")
    @TableField("title")
    private String title;

    @ApiModelProperty("Slidev访问地址")
    @TableField("url")
    private String url;

    @ApiModelProperty("课件类型: 0-Slidev网页, 1-腾讯文档嵌入")
    @TableField("type")
    private Integer type;

    @ApiModelProperty("关联题单ID")
    @TableField("problem_list_id")
    private Long problemListId;

    @ApiModelProperty("Slidev Markdown源码")
    @TableField("content")
    private String content;

    @ApiModelProperty("简介")
    @TableField("description")
    private String description;

    @ApiModelProperty("0-私有, 1-公开")
    @TableField("privacy")
    private Integer privacy;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("is_deleted")
    private Integer isDeleted;
}