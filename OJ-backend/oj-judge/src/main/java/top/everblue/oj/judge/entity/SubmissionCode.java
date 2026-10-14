package top.everblue.oj.judge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 评测提交源码（V50 从 submission 拆出，1:1 懒加载）。
 * <p>
 * 主表 submission 不再存 code（longtext 是体积大头且判题回写反复重写），
 * 需要源码的路径（判题/回放/评阅/详情/查重）从这里按 submissionId 取。
 */
@Data
@TableName("submission_code")
public class SubmissionCode implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 提交 ID（1:1，主键即外键） */
    @TableId(value = "submission_id", type = IdType.INPUT)
    private Long submissionId;

    /** 提交源码 */
    private String code;
}
