package top.everblue.oj.analytics.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 触发查重请求（班级内同题比对）。
 */
@Data
public class RunPlagiarismRequest {

    @NotNull(message = "班级 ID 不能为空")
    private Long classId;

    @NotNull(message = "题目 ID 不能为空")
    private Long problemId;
}
