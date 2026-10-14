package top.everblue.oj.judge.application;

import lombok.AllArgsConstructor;
import lombok.Getter;
import top.everblue.oj.judge.entity.Submission;

import java.time.LocalDateTime;

/** A submission result intentionally excludes source code and judge internals. */
@Getter
@AllArgsConstructor
public class SubmissionResultResponse {
    private final Long id;
    private final Long problemId;
    private final Long contestId;
    private final Byte status;
    private final String judgePhase;
    private final Integer score;
    private final Integer runTime;
    private final Integer runMemory;
    private final String errorMessage;
    private final LocalDateTime createTime;

    public static SubmissionResultResponse from(Submission submission) {
        return new SubmissionResultResponse(
                submission.getId(),
                submission.getProblemId(),
                submission.getContestId(),
                submission.getStatus(),
                submission.getJudgePhase(),
                submission.getScore(),
                submission.getRunTime(),
                submission.getRunMemory(),
                submission.getErrorMessage(),
                submission.getCreateTime()
        );
    }
}
