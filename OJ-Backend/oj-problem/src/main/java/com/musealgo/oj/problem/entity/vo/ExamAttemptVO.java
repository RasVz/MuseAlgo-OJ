package com.musealgo.oj.problem.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 作答会话 VO（含剩余秒数与当前用户已提交的客观题答案/判分）。 */
@Data
public class ExamAttemptVO {

    private Long id;
    private Long paperId;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime submitTime;
    private Integer objectiveScore;
    private Integer codingScore;
    private Integer totalScore;
    private Long remainingSeconds;
    private Boolean gradingPending = false;
    private List<ExamPaperVO.ExamAttemptAnswerVO> answers = new ArrayList<>();
    private List<ExamPaperVO.ExamQuestionResultVO> results = new ArrayList<>();
}
