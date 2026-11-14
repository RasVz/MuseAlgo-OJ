package com.musealgo.oj.contest.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 比赛参赛卷上下文：只包含作答期允许看到的比赛与题目导航信息。 */
@Data
public class ContestAnswerPaperVO {

    private Long contestId;
    private String title;
    private String mode;
    /** NOT_STARTED / RUNNING / ENDED；VP 进行中同样返回 RUNNING。 */
    private String status;
    private LocalDateTime serverTime;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime deadline;
    private Long remainingSeconds;
    private Boolean canSubmit;
    private Boolean canSelfTest;
    private Boolean canFinish;
    /** VERDICT_TIME_MEMORY（ACM）/ SUBMITTED_ONLY（OI 赛中）/ FULL（赛后或 VP 结束）。 */
    private String resultVisibility;
    private String registrationStatus;
    private Boolean registrationReviewRequired;
    private List<ProblemItem> problems = new ArrayList<>();

    @Data
    public static class ProblemItem {
        private Long problemId;
        private Integer order;
        private String title;
        private Integer score;
        private String myStatus;
        private Boolean pending;
    }
}
