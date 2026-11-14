package com.musealgo.oj.contest.entity.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 比赛参赛卷单题白名单题面：不含难度、标签、通过率、提示与标准答案。 */
@Data
public class ContestAnswerProblemVO {

    private Long contestId;
    private Long problemId;
    private Integer order;
    private String title;
    private String content;
    private String inputDesc;
    private String outputDesc;
    private List<SampleVO> samples = new ArrayList<>();
    private Integer timeLimit;
    private Integer memoryLimit;
    private Integer score;
    private String myStatus;
    private Boolean canSubmit;

    @Data
    public static class SampleVO {
        private String input;
        private String output;
    }
}
