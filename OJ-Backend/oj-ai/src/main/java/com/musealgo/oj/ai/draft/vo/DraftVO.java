package com.musealgo.oj.ai.draft.vo;

import com.musealgo.oj.ai.draft.DraftBrief;
import com.musealgo.oj.ai.draft.DraftContent;
import com.musealgo.oj.ai.draft.DraftIssue;
import com.musealgo.oj.ai.draft.DraftSeverity;
import com.musealgo.oj.ai.entity.ProblemDraft;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 草稿列表项。
 *
 * @param cancellable 由服务端按「PENDING 且 phase=QUEUED」判定；前端不要自己按 status 推
 * @param elapsedMs   已耗时（未完成时按当前时间计算），用于「已等待 12s」这类真实反馈
 */
public record DraftVO(
        Long id,
        String groupId,
        Integer variantNo,
        String status,
        String phase,
        String title,
        Integer difficultyScore,
        String difficultyBand,
        int errorCount,
        int warnCount,
        int infoCount,
        Long problemId,
        String message,
        LocalDateTime createTime,
        LocalDateTime finishTime,
        long elapsedMs,
        List<String> savedFields,
        boolean cancellable,
        Long parentId,
        String targetField,
        String instruction) {

    public static DraftVO of(ProblemDraft draft, DraftContent content, List<DraftIssue> issues, LocalDateTime now) {
        int errors = 0;
        int warnings = 0;
        int infos = 0;
        if (issues != null) {
            for (DraftIssue issue : issues) {
                if (issue.severity() == DraftSeverity.ERROR) {
                    errors++;
                } else if (issue.severity() == DraftSeverity.WARN) {
                    warnings++;
                } else {
                    infos++;
                }
            }
        }
        Integer score = content == null ? null : content.difficultyScore();
        List<String> applied = draft.getSavedFields() == null || draft.getSavedFields().isBlank()
                ? List.of()
                : Arrays.stream(draft.getSavedFields().split(",")).filter(value -> !value.isBlank()).toList();
        boolean cancellable = "PENDING".equals(draft.getStatus()) && "QUEUED".equals(draft.getPhase());
        // -1 是保存时的占位标记（建题完成前），不能透出去让界面显示「已保存为 #-1」
        Long problemId = draft.getProblemId() != null && draft.getProblemId() > 0 ? draft.getProblemId() : null;
        return new DraftVO(draft.getId(), draft.getGroupId(), draft.getVariantNo(), draft.getStatus(),
                draft.getPhase(), draft.getTitle(), score,
                score == null ? null : DraftBrief.bandOf(score).label(),
                errors, warnings, infos, problemId, draft.getMessage(),
                draft.getCreateTime(), draft.getFinishTime(),
                elapsed(draft.getCreateTime(), draft.getFinishTime(), now), applied, cancellable,
                draft.getParentId(), draft.getTargetField(), draft.getInstruction());
    }

    private static long elapsed(LocalDateTime createTime, LocalDateTime finishTime, LocalDateTime now) {
        if (createTime == null) {
            return 0;
        }
        LocalDateTime end = finishTime == null ? now : finishTime;
        return Math.max(0, Duration.between(createTime, end).toMillis());
    }
}
