package top.everblue.oj.ai.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** AI 代码复盘请求。 */
@Data
public class AiReviewRequest {

    @NotNull(message = "提交 ID 不能为空")
    private Long submissionId;

    /** 题目 ID（仅用于用量流水记录；比赛拦截以服务端查询为准） */
    private Long problemId;
}
