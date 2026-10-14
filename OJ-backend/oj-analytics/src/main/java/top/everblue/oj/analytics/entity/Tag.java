package top.everblue.oj.analytics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 标签字典（三层知识图谱）。
 * <p>
 * 层级关系通过 {@code parent_id} 自引用：
 * <ul>
 *   <li>{@code parent_id IS NULL} → 知识域（如"算法"、"数据结构"）</li>
 *   <li>{@code parent_id} 指向知识域 → 知识点（如"动态规划"）</li>
 *   <li>{@code parent_id} 指向知识点 → 具体标签（如"背包DP"）</li>
 * </ul>
 */
@Getter
@Setter
@TableName("tag")
public class Tag implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("code")
    private String code;

    @TableField("name")
    private String name;

    @TableField("parent_id")
    private Long parentId;

    @TableField("dimension")
    private String dimension;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}