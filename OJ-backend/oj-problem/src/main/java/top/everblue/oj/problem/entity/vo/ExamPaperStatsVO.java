package top.everblue.oj.problem.entity.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 试卷成绩统计。 */
@Data
public class ExamPaperStatsVO {

    private long attemptCount;
    private double avgObjective;
    private double avgCoding;
    private double avgTotal;

    private List<QuestionStatVO> questionStats = new ArrayList<>();
    private List<ScoreBucketVO> distribution = new ArrayList<>();
    private List<GespLevelVO> gespDistribution = new ArrayList<>();

    @Data
    public static class QuestionStatVO {
        private Long questionId;
        private Integer questionOrder;
        private String statement;
        private long attemptCount;
        private long correctCount;
        private double correctRate;
    }

    @Data
    public static class ScoreBucketVO {
        private String bucket;
        private int count;
    }

    @Data
    public static class GespLevelVO {
        private int level;   // 0=未达一级
        private int count;
    }
}
