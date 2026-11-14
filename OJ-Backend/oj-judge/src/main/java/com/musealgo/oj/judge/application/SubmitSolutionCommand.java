package com.musealgo.oj.judge.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Request payload for a solution submission. The actor always comes from JWT. */
@Data
public class SubmitSolutionCommand {

    @NotNull
    private Long problemId;

    private Long contestId;

    /** 成套试卷作答会话；与 contestId 互斥。 */
    private Long attemptId;

    /** 客户端幂等键，用于网络重试去重。 */
    @Size(max = 64)
    private String clientRequestId;

    @NotBlank
    private String language;

    @NotBlank
    private String code;
}

