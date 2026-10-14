package top.everblue.oj.analytics.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 错题归因与训练计划（A3）。 */
@Data
public class WeaknessResultVO {

    private List<WeaknessVO> weaknesses = new ArrayList<>();
    private List<RecommendationVO> recommendations = new ArrayList<>();
    private String aiAdvice;
    private String ruleBasedAdvice;
    private LocalDateTime generatedAt = LocalDateTime.now();

    @Data
    public static class WeaknessVO {
        private Long tagId;
        private String tagName;
        private int mastery;      // 0-100
        private int failCount;    // 尝试数 - 通过题数
        private String level;     // 优秀/良好/及格/薄弱
    }

    @Data
    public static class RecommendationVO {
        private Long problemId;
        private String title;
        private Integer difficulty;
    }
}
