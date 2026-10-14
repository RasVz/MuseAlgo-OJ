package top.everblue.oj.problem.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 试卷保存 DTO（创建/更新共用）。 */
@Data
public class ExamPaperDTO {

    private Long id;

    @NotBlank(message = "试卷标题不能为空")
    private String title;

    private String description;

    @NotBlank(message = "试卷分类不能为空")
    private String category;   // GESP / CSP_J / CSP_S / NOIP / MOCK

    private String level;

    @NotNull(message = "作答时长不能为空")
    private Integer durationMinutes;

    @NotNull(message = "满分不能为空")
    private Integer totalScore;

    private Integer isPublished;

    private List<ExamPaperProblemItem> problems = new ArrayList<>();

    /** 客观题（单选/多选/判断） */
    private List<ExamQuestionItem> questions = new ArrayList<>();

    @Data
    public static class ExamPaperProblemItem {
        @NotNull(message = "题目 ID 不能为空")
        private Long problemId;

        private Integer score = 0;
    }

    @Data
    public static class ExamQuestionItem {
        @NotBlank(message = "题型不能为空")
        private String questionType;   // SINGLE / MULTIPLE / JUDGE

        @NotBlank(message = "题干不能为空")
        private String statement;

        private List<ExamOptionItem> options = new ArrayList<>();

        @NotBlank(message = "标准答案不能为空")
        private String answer;

        private String explanation;

        @NotNull(message = "分值不能为空")
        private Integer score;
    }

    @Data
    public static class ExamOptionItem {
        private String key;   // A / B / C ...
        private String text;
    }
}
