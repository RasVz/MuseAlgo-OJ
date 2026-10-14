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
 * 班级成员表
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
@Getter
@Setter
@TableName("class_student")
@ApiModel(value = "ClassStudent对象", description = "班级成员表")
public class ClassStudent implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("class_id")
    private Long classId;

    @TableField("student_id")
    private Long studentId;

    @TableField("join_time")
    private LocalDateTime joinTime;

    @TableField("remark")
    private String remark;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
