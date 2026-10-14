package top.everblue.oj.judge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户代码草稿表
 */
@Data
@TableName("user_code_draft")
public class UserCodeDraft {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long problemId;

    private String language;

    private String code;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}