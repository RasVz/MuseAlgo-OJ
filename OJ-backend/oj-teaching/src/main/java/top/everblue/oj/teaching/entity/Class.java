package top.everblue.oj.teaching.entity;

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
 * 班级表
 */
@Getter
@Setter
@TableName("class")
@ApiModel(value = "ClassGroup对象", description = "班级表")
public class Class implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty("班级名称")
    @TableField("name")
    private String name;

    @ApiModelProperty("创建教师ID")
    @TableField("teacher_id")
    private Long teacherId;

    @ApiModelProperty("学生人数")
    @TableField("student_count")
    private Integer studentCount;

    @ApiModelProperty("邀请码")
    @TableField("invite_code")
    private String inviteCode;

    @ApiModelProperty("简介")
    @TableField("description")
    private String description;

    @ApiModelProperty("所属机构")
    @TableField("school")
    private String school;

    @TableField("create_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    @TableField("update_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;

    @TableField("is_deleted")
    private Integer isDeleted;
}
