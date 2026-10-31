package com.musealgo.oj.judge.dto;

import com.musealgo.oj.judge.entity.RejudgeItem;

import java.time.LocalDateTime;
import java.util.List;

/** 重判操作查询/创建响应（T8）。 */
public record RejudgeOperationVO(
        Long id,
        Long operatorId,
        String operatorName,
        String reason,
        String scopeType,
        String scopeJson,
        String status,
        int totalItems,
        int succeededItems,
        int failedItems,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String errorMessage,
        List<Item> items
) {
    /** 条目视图。 */
    public record Item(
            Long id,
            Long submissionId,
            Long problemId,
            Long userId,
            Long contestId,
            Long vpAttemptId,
            String testcaseDigest,
            String oldResultJson,
            String newResultJson,
            String status,
            LocalDateTime createdAt,
            LocalDateTime finishedAt,
            String errorMessage
    ) {
        public static Item from(RejudgeItem item) {
            return new Item(item.getId(), item.getSubmissionId(), item.getProblemId(), item.getUserId(),
                    item.getContestId(), item.getVpAttemptId(), item.getTestcaseDigest(),
                    item.getOldResultJson(), item.getNewResultJson(), item.getStatus(),
                    item.getCreatedAt(), item.getFinishedAt(), item.getErrorMessage());
        }
    }
}
