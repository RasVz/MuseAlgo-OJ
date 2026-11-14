package com.musealgo.oj.ai.review;

import com.musealgo.oj.ai.dto.AiTeachingOptions.HelpMode;
import java.time.LocalDateTime;

/** 白名单投影：绝不把内部输入、执行凭据、权限快照或请求键送给浏览器。 */
public record ReviewView(Long id, Long userId, Long submissionId, Long problemId, String problemTitle,
        String language, Byte submissionStatus, Integer score, Integer runTime, Integer runMemory,
        Long parentReviewId, String relationType, HelpMode helpMode, String studentQuestion, String status,
        String errorCode, String message, LocalDateTime createTime, LocalDateTime startTime,
        LocalDateTime finishTime, Long latencyMs, String model, String serviceName) {
    public static ReviewView of(SubmissionReview r) {
        return new ReviewView(r.getId(), r.getUserId(), r.getSubmissionId(), r.getProblemId(), r.getProblemTitle(),
                r.getLanguage(), r.getSubmissionStatus(), r.getScore(), r.getRunTime(), r.getRunMemory(),
                r.getParentReviewId(), r.getRelationType(), HelpMode.valueOf(r.getHelpMode()), r.getStudentQuestion(),
                r.getStatus(), r.getErrorCode(), r.getMessage(), r.getCreateTime(), r.getStartTime(),
                r.getFinishTime(), r.getLatencyMs(), r.getModel(), r.getServiceName());
    }
}
