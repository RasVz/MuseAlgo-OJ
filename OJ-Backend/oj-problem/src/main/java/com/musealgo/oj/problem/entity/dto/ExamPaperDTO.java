package com.musealgo.oj.problem.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 试卷保存 DTO（创建/更新共用）。 */
@Data
public class ExamPaperDTO {

    private Long id;

    @NotBlank(message = "试卷标题不能为空")
    @Size(max = 200, message = "试卷标题不能超过 200 个字符")
    private String title;

    private String description;

    @NotBlank(message = "试卷分类不能为空")
    @Size(max = 32, message = "试卷分类不能超过 32 个字符")
    private String category;   // GESP / CSP_J / CSP_S / NOIP / MOCK

    private String level;

    @NotNull(message = "作答时长不能为空")
    @Min(value = 1, message = "作答时长至少 1 分钟")
    @Max(value = 600, message = "作答时长不能超过 600 分钟")
    private Integer durationMinutes;

    @NotNull(message = "满分不能为空")
    @Min(value = 1, message = "试卷满分至少为 1 分")
    @Max(value = 1000, message = "试卷满分不能超过 1000 分")
    private Integer totalScore;

    @Min(value = 0, message = "发布状态只能为 0 或 1")
    @Max(value = 1, message = "发布状态只能为 0 或 1")
    private Integer isPublished;

    @Size(max = 500, message = "编程题不能超过 500 道")
    private List<@NotNull @Valid ExamPaperProblemItem> problems = new ArrayList<>();

    /** 客观题（单选/多选/判断） */
    @Size(max = 500, message = "客观题不能超过 500 道")
    private List<@NotNull @Valid ExamQuestionItem> questions = new ArrayList<>();

    @Data
    public static class ExamPaperProblemItem {
        @NotNull(message = "题目 ID 不能为空")
        @Min(value = 1, message = "题目 ID 不合法")
        private Long problemId;

        @NotNull(message = "编程题分值不能为空")
        @Min(value = 0, message = "编程题分值不能小于 0")
        @Max(value = 1000, message = "编程题分值不能超过 1000")
        private Integer score = 0;
    }

    @Data
    public static class ExamQuestionItem {
        @NotBlank(message = "题型不能为空")
        @Size(max = 16, message = "题型不能超过 16 个字符")
        private String questionType;   // SINGLE / MULTIPLE / JUDGE

        @NotBlank(message = "题干不能为空")
        @Size(max = 10000, message = "题干不能超过 10000 个字符")
        private String statement;

        @Size(max = 26, message = "选项不能超过 26 个")
        private List<@NotNull @Valid ExamOptionItem> options = new ArrayList<>();

        @NotBlank(message = "标准答案不能为空")
        @Size(max = 255, message = "标准答案不能超过 255 个字符")
        private String answer;

        @Size(max = 10000, message = "解析不能超过 10000 个字符")
        private String explanation;

        @NotNull(message = "分值不能为空")
        @Min(value = 0, message = "客观题分值不能小于 0")
        @Max(value = 1000, message = "客观题分值不能超过 1000")
        private Integer score;
    }

    @Data
    public static class ExamOptionItem {
        @NotBlank(message = "选项标识不能为空")
        @Size(max = 8, message = "选项标识不能超过 8 个字符")
        private String key;   // A / B / C ...
        @NotBlank(message = "选项内容不能为空")
        @Size(max = 2000, message = "选项内容不能超过 2000 个字符")
        private String text;
    }
}
