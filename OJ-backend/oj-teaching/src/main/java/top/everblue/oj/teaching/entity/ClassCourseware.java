package top.everblue.oj.teaching.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 班级课件关联表
 * </p>
 *
 * @author RasVz
 * @since 2025-12-26
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("class_courseware")
public class ClassCourseware implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long classId;

    // 课件ID
    private Long coursewareId;

    // 排序
    private Integer sort;

    // 0-隐藏 1-显示
    private Boolean isVisible;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}