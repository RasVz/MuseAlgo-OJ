package com.musealgo.oj.problem.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 成套试卷整卷答题页上下文。
 *
 * <p>只下发作答所需字段：客观题不含正确答案/解析，编程题不含难度、标签、通过率；
 * 是否可提交、是否仍在判题由服务端判定，前端不自行推断。</p>
 */
@Data
public class ExamPaperAnswerVO {

    private Long paperId;
    private Long attemptId;
    private String title;
    private String description;
    private String status;
    private LocalDateTime serverTime;
    private LocalDateTime deadline;
    private Long remainingSeconds;
    private Boolean gradingPending;
    private Integer objectiveScore;
    private Integer codingScore;
    private Integer totalScore;
    private Boolean canSubmit;
    private Boolean canSelfTest;
    private List<ObjectiveQuestionVO> objectiveQuestions = new ArrayList<>();
    private List<CodingProblemVO> codingProblems = new ArrayList<>();

    @Data
    public static class ObjectiveQuestionVO {
        private Long id;
        private String questionType;
        private String statement;
        private List<Map<String, String>> options;
        private Integer score;
        private Integer questionOrder;
        private String myAnswer;
        private Boolean correct;
        private Integer gainedScore;
    }

    @Data
    public static class CodingProblemVO {
        private Long problemId;
        private Integer problemOrder;
        private String title;
        private Integer score;
        private String myStatus;
        private Integer myBestScore;
        private Integer attemptCount;
        private Boolean pending;
    }
}
