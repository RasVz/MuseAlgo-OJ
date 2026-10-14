package top.everblue.oj.problem.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 试卷详情 VO（含题目列表）。 */
@Data
public class ExamPaperVO {

    private Long id;
    private String title;
    private String description;
    private String category;
    private String level;
    private Integer durationMinutes;
    private Integer totalScore;
    private Integer problemCount;
    private Integer isPublished;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    private List<ExamPaperProblemVO> problems = new ArrayList<>();

    /** 客观题（答案字段仅在管理员请求时填充） */
    private List<ExamQuestionVO> questions = new ArrayList<>();

    @Data
    public static class ExamPaperProblemVO {
        private Long problemId;
        private Integer problemOrder;
        private Integer score;
        private String title;
        private Integer difficulty;
    }

    @Data
    public static class ExamQuestionVO {
        private Long id;
        private String questionType;
        private String statement;
        private List<Map<String, String>> options;   // [{key,text}]
        private String answer;                        // 学生作答后回显/管理员编辑用
        private String explanation;                   // 解析（仅管理员编辑/判分后可见）
        private Integer score;
        private Integer questionOrder;
    }

    /** 客观题判分结果（提交后返回） */
    @Data
    public static class ExamQuestionResultVO {
        private Long questionId;
        private Boolean correct;
        private Integer score;
        private String correctAnswer;   // 判分后回显标准答案，便于订正
        private String myAnswer;
        private String explanation;     // 判分后回显解析
    }

    /** 成绩明细行 */
    @Data
    public static class ExamAttemptRowVO {
        private Long id;
        private Long userId;
        private String username;
        private String status;
        private java.time.LocalDateTime startTime;
        private java.time.LocalDateTime endTime;
        private java.time.LocalDateTime submitTime;
        private Integer objectiveScore;
        private Integer codingScore;
        private Integer totalScore;
        private Integer gespLevel;
    }
}
