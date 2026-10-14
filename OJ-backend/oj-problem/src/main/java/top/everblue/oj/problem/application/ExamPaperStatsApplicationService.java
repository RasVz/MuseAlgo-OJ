package top.everblue.oj.problem.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.system.SystemConfigQuery;
import top.everblue.oj.api.user.UserDisplayInfo;
import top.everblue.oj.api.user.UserQuery;
import top.everblue.oj.problem.domain.GespLevelEstimator;
import top.everblue.oj.problem.entity.ExamAnswer;
import top.everblue.oj.problem.entity.ExamAttempt;
import top.everblue.oj.problem.entity.ExamQuestion;
import top.everblue.oj.problem.entity.vo.ExamPaperStatsVO;
import top.everblue.oj.problem.entity.vo.ExamPaperVO;
import top.everblue.oj.problem.mapper.ExamAnswerMapper;
import top.everblue.oj.problem.mapper.ExamAttemptMapper;
import top.everblue.oj.problem.mapper.ExamQuestionMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 训练试卷统计域：试卷统计 / 作答明细分页 / 作答 CSV 导出。
 * <p>
 * 从 {@link top.everblue.oj.problem.service.impl.ExamPaperServiceImpl} 拆出
 * （2026-08-15 大服务类拆分）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamPaperStatsApplicationService {

    private final ExamAttemptMapper examAttemptMapper;
    private final ExamQuestionMapper examQuestionMapper;
    private final ExamAnswerMapper examAnswerMapper;
    private final UserQuery userQuery;
    private final SystemConfigQuery systemConfigQuery;

    /** 试卷作答统计（人均分 / 每题正确率 / 分数段分布 / GESP 等第分布）。 */
    public ExamPaperStatsVO paperStats(Long paperId) {
        List<ExamAttempt> attempts = latestCompletedAttempts(paperId);
        ExamPaperStatsVO stats = new ExamPaperStatsVO();
        if (attempts.isEmpty()) {
            return stats;
        }
        stats.setAttemptCount(attempts.size());
        stats.setAvgObjective(attempts.stream()
                .mapToInt(a -> a.getObjectiveScore() == null ? 0 : a.getObjectiveScore())
                .average().orElse(0));
        stats.setAvgCoding(attempts.stream()
                .mapToInt(a -> a.getCodingScore() == null ? 0 : a.getCodingScore())
                .average().orElse(0));
        stats.setAvgTotal(attempts.stream()
                .mapToInt(a -> a.getTotalScore() == null ? 0 : a.getTotalScore())
                .average().orElse(0));

        List<ExamQuestion> questions = examQuestionMapper.selectList(
                new LambdaQueryWrapper<ExamQuestion>().eq(ExamQuestion::getPaperId, paperId)
                        .orderByAsc(ExamQuestion::getQuestionOrder));
        for (ExamQuestion question : questions) {
            List<ExamAnswer> answers = examAnswerMapper.selectList(
                    new LambdaQueryWrapper<ExamAnswer>()
                            .eq(ExamAnswer::getPaperId, paperId)
                            .eq(ExamAnswer::getQuestionId, question.getId())
                            .in(ExamAnswer::getAttemptId, attempts.stream().map(ExamAttempt::getId).toList()));
            long correct = answers.stream()
                    .filter(a -> a.getIsCorrect() != null && a.getIsCorrect() == 1).count();
            ExamPaperStatsVO.QuestionStatVO vo = new ExamPaperStatsVO.QuestionStatVO();
            vo.setQuestionId(question.getId());
            vo.setQuestionOrder(question.getQuestionOrder());
            vo.setStatement(truncate(question.getStatement(), 80));
            vo.setAttemptCount(answers.size());
            vo.setCorrectCount(correct);
            vo.setCorrectRate(answers.isEmpty() ? 0 : (double) correct / answers.size() * 100);
            stats.getQuestionStats().add(vo);
        }

        Map<String, Integer> buckets = new LinkedHashMap<>();
        buckets.put("0", 0);
        buckets.put("1-59", 0);
        buckets.put("60-69", 0);
        buckets.put("70-79", 0);
        buckets.put("80-89", 0);
        buckets.put("90-100", 0);
        for (ExamAttempt attempt : attempts) {
            int total = attempt.getTotalScore() == null ? 0 : attempt.getTotalScore();
            buckets.merge(bucketOf(total), 1, Integer::sum);
        }
        buckets.forEach((bucket, count) -> {
            ExamPaperStatsVO.ScoreBucketVO vo = new ExamPaperStatsVO.ScoreBucketVO();
            vo.setBucket(bucket);
            vo.setCount(count);
            stats.getDistribution().add(vo);
        });

        // GESP 等第分布（E4，阈值可配）
        Map<Integer, Integer> thresholds = GespLevelEstimator.thresholdsFromConfig(
                systemConfigQuery.get("exam.gesp-thresholds", null));
        Map<Integer, Integer> levelCounts = new LinkedHashMap<>();
        for (int level = 0; level <= 8; level++) {
            levelCounts.put(level, 0);
        }
        for (ExamAttempt attempt : attempts) {
            int total = attempt.getTotalScore() == null ? 0 : attempt.getTotalScore();
            levelCounts.merge(GespLevelEstimator.estimate(total, thresholds), 1, Integer::sum);
        }
        levelCounts.forEach((level, count) -> {
            ExamPaperStatsVO.GespLevelVO vo = new ExamPaperStatsVO.GespLevelVO();
            vo.setLevel(level);
            vo.setCount(count);
            stats.getGespDistribution().add(vo);
        });
        return stats;
    }

    /** 某卷作答明细分页（不含进行中会话，按交卷时间倒序，带用户名）。 */
    public Page<ExamPaperVO.ExamAttemptRowVO> listAttempts(Long paperId, Integer page, Integer size) {
        Page<ExamAttempt> raw = examAttemptMapper.selectPage(
                new Page<>(page == null ? 1 : page, size == null ? 20 : size),
                new LambdaQueryWrapper<ExamAttempt>()
                        .eq(ExamAttempt::getPaperId, paperId)
                        .ne(ExamAttempt::getStatus, ExamAttempt.STATUS_RUNNING)
                        .orderByDesc(ExamAttempt::getSubmitTime));
        Page<ExamPaperVO.ExamAttemptRowVO> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        if (raw.getRecords().isEmpty()) {
            return result;
        }
        Set<Long> userIds = raw.getRecords().stream()
                .map(ExamAttempt::getUserId).collect(Collectors.toSet());
        Map<Long, UserDisplayInfo> userMap = userQuery.findDisplayInfoBatch(userIds);
        List<ExamPaperVO.ExamAttemptRowVO> rows = raw.getRecords().stream().map(attempt -> {
            ExamPaperVO.ExamAttemptRowVO row = new ExamPaperVO.ExamAttemptRowVO();
            row.setId(attempt.getId());
            row.setUserId(attempt.getUserId());
            UserDisplayInfo user = userMap.get(attempt.getUserId());
            row.setUsername(user == null ? "未知用户"
                    : user.nickname() != null && !user.nickname().isBlank()
                            ? user.nickname() : user.username());
            row.setStatus(attempt.getStatus());
            row.setStartTime(attempt.getStartTime());
            row.setEndTime(attempt.getEndTime());
            row.setSubmitTime(attempt.getSubmitTime());
            row.setObjectiveScore(attempt.getObjectiveScore());
            row.setCodingScore(attempt.getCodingScore());
            row.setTotalScore(attempt.getTotalScore());
            row.setGespLevel(GespLevelEstimator.estimate(
                    attempt.getTotalScore() == null ? 0 : attempt.getTotalScore(),
                    GespLevelEstimator.thresholdsFromConfig(
                            systemConfigQuery.get("exam.gesp-thresholds", null))));
            return row;
        }).toList();
        result.setRecords(rows);
        return result;
    }

    /** 作答明细 CSV（教师导出用，最多 1 万行）。 */
    public String buildAttemptsCsv(Long paperId) {
        List<ExamPaperVO.ExamAttemptRowVO> rows = listAttempts(paperId, 1, 10000).getRecords();
        StringBuilder csv = new StringBuilder();
        csv.append("会话ID,用户名,状态,开始时间,结束时间,交卷时间,客观题,编程题,总分,GESP等第\n");
        for (ExamPaperVO.ExamAttemptRowVO row : rows) {
            csv.append(escapeCsv(String.valueOf(row.getId()))).append(',')
                    .append(escapeCsv(row.getUsername())).append(',')
                    .append(escapeCsv(row.getStatus())).append(',')
                    .append(escapeCsv(String.valueOf(row.getStartTime()))).append(',')
                    .append(escapeCsv(String.valueOf(row.getEndTime()))).append(',')
                    .append(escapeCsv(String.valueOf(row.getSubmitTime()))).append(',')
                    .append(row.getObjectiveScore() == null ? 0 : row.getObjectiveScore()).append(',')
                    .append(row.getCodingScore() == null ? 0 : row.getCodingScore()).append(',')
                    .append(row.getTotalScore() == null ? 0 : row.getTotalScore()).append(',')
                    .append(row.getGespLevel() == null ? 0 : row.getGespLevel()).append('\n');
        }
        return csv.toString();
    }

    /** 每人最近一次已交卷会话（按交卷时间倒序去重）。 */
    private List<ExamAttempt> latestCompletedAttempts(Long paperId) {
        List<ExamAttempt> completed = examAttemptMapper.selectList(
                new LambdaQueryWrapper<ExamAttempt>()
                        .eq(ExamAttempt::getPaperId, paperId)
                        .ne(ExamAttempt::getStatus, ExamAttempt.STATUS_RUNNING)
                        .orderByDesc(ExamAttempt::getSubmitTime)
                        .orderByDesc(ExamAttempt::getId));
        Map<Long, ExamAttempt> latestByUser = new LinkedHashMap<>();
        for (ExamAttempt attempt : completed) {
            latestByUser.putIfAbsent(attempt.getUserId(), attempt);
        }
        return new ArrayList<>(latestByUser.values());
    }

    public static String bucketOf(int score) {
        if (score <= 0) return "0";
        if (score < 60) return "1-59";
        if (score < 70) return "60-69";
        if (score < 80) return "70-79";
        if (score < 90) return "80-89";
        return "90-100";
    }

    public static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
