package top.everblue.oj.judge.entity.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 提交详情 VO（经过权限校验后，才包含源码 code）
 */
@Data
public class SubmissionDetailVO {
    private Long id;
    private Long problemId;
    private String problemTitle;
    private Long userId;
    private String username;
    private String userAvatar;
    private Byte status;
    private String judgePhase;
    private String language;
    private Integer runTime;
    private Integer runMemory;
    private Integer score;
    private String errorMessage;
    private LocalDateTime createTime;

    /**
     * 代码（仅在有权限时返回）
     */
    private String code;
}
