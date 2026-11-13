package com.musealgo.oj.problem.entity.vo;

import com.musealgo.oj.problem.entity.ProblemSample;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 成套试卷答题页的编程题白名单题面。 */
@Data
public class ExamPaperAnswerProblemVO {

    private Long problemId;
    private String title;
    private String content;
    private String inputDesc;
    private String outputDesc;
    private List<ProblemSample> samples = new ArrayList<>();
    private Integer timeLimit;
    private Integer memoryLimit;
    private Integer score;
    private String myStatus;
    private Integer myBestScore;
    private Boolean canSubmit;
}
