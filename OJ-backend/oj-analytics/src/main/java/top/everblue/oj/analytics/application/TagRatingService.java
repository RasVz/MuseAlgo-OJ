package top.everblue.oj.analytics.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.everblue.oj.analytics.entity.ProblemTag;
import top.everblue.oj.analytics.entity.Tag;
import top.everblue.oj.analytics.entity.UserTagRating;
import top.everblue.oj.analytics.mapper.ProblemTagMapper;
import top.everblue.oj.analytics.mapper.TagMapper;
import top.everblue.oj.analytics.mapper.UserTagRatingMapper;
import top.everblue.oj.api.problem.ProblemDigest;
import top.everblue.oj.api.problem.ProblemQuery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 标签 ELO 评分服务。
 * <p>
 * 核心算法：学生 AC 一道题后，根据题目的所有 tag 逐一计算增益。<br>
 * gain = K * (1 − 1/(1 + 10^((D−R)/400)))，其中 D 为题难度映射值，R 为当前评分。
 * 评分只增不减（upsert 用 GREATEST），上限为题难度 + 200。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagRatingService {

    private static final int K = 50;                  // 单题最大增益
    private static final int DEFAULT_RATING = 800;     // 初始评分
    private static final int CAP_MARGIN = 200;         // 评分上限 = 题难度 + 200

    private final UserTagRatingMapper userTagRatingMapper;
    private final ProblemTagMapper problemTagMapper;
    private final TagMapper tagMapper;
    private final ProblemQuery problemQuery;

    /** 六维映射 */
    public static final Map<String, String> DIMENSION_LABELS = Map.of(
            "DATA_STRUCTURE", "数据结构",
            "ALGORITHM",      "算法基础",
            "DP",             "动态规划",
            "GRAPH",          "图论",
            "STRING",         "字符串",
            "MATH",           "数学"
    );

    /**
     * 学生 AC 某题后更新所有关联 tag 的评分。
     */
    public void updateRating(Long userId, Long problemId) {
        ProblemDigest problem = problemQuery.findDigestBatch(List.of(problemId))
                .stream().findFirst().orElse(null);
        if (problem == null) {
            return;
        }
        int difficultyRating = resolveDifficulty(problem.difficulty());

        List<ProblemTag> relations = problemTagMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProblemTag>()
                        .eq(ProblemTag::getProblemId, problemId));
        if (relations.isEmpty()) {
            return;
        }

        for (ProblemTag pt : relations) {
            Long tagId = pt.getTagId();
            // 查询当前评分（无记录则默认 800）
            List<top.everblue.oj.analytics.entity.UserTagRating> existing =
                    userTagRatingMapper.selectByUserAndTags(userId, List.of(tagId));
            int current = existing.isEmpty() ? DEFAULT_RATING
                    : existing.get(0).getRating() != null ? existing.get(0).getRating() : DEFAULT_RATING;

            int gain = calcGain(current, difficultyRating);
            int newRating = Math.min(current + gain, difficultyRating + CAP_MARGIN);

            userTagRatingMapper.upsert(userId, tagId, newRating);
            log.debug("TagRating user={} tag={} {}→{} gain={}", userId, tagId, current, newRating, gain);
        }
    }

    /** ELO 增益公式 */
    int calcGain(int currentRating, int problemDifficulty) {
        double expected = 1.0 / (1.0 + Math.pow(10, (problemDifficulty - currentRating) / 400.0));
        return (int) Math.round(K * (1.0 - expected));
    }

    /** 难度解析：直接使用 800-3000 的难度分数作为 ELO 的 D 值 */
    static int resolveDifficulty(Integer difficulty) {
        if (difficulty == null || difficulty < DEFAULT_RATING) return DEFAULT_RATING;
        return difficulty;
    }

    // ==================== 只读查询（个人主页雷达/推荐/学习计划用） ====================

    /** 全部知识域（六维雷达维度）。 */
    public List<Tag> selectDimensions() {
        return tagMapper.selectDimensions();
    }

    /** 指定知识域下的全部子标签。 */
    public List<Tag> selectChildrenByDimensions(Collection<Long> dimensionIds) {
        if (dimensionIds == null || dimensionIds.isEmpty()) {
            return List.of();
        }
        return tagMapper.selectList(
                new LambdaQueryWrapper<Tag>().in(Tag::getParentId, dimensionIds));
    }

    /** 用户在某批标签上的评分记录（空集合 = 查询全部）。 */
    public List<UserTagRating> selectRatings(Long userId, Collection<Long> tagIds) {
        List<Long> ids = tagIds == null ? List.of() : new ArrayList<>(tagIds);
        return userTagRatingMapper.selectByUserAndTags(userId, ids);
    }

    /** 用户全部标签评分记录。 */
    public List<UserTagRating> selectRatingsAll(Long userId) {
        return userTagRatingMapper.selectByUserAndTags(userId, List.of());
    }

    /** 标签名批量查询（tagId → 名称）。 */
    public Map<Long, String> selectTagNames(Collection<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return Map.of();
        }
        return tagMapper.selectBatchIds(tagIds).stream()
                .collect(Collectors.toMap(Tag::getId, Tag::getName));
    }

    /** 标签 → 题目关联批量查询（按 tagId 过滤）。 */
    public List<ProblemTag> findProblemTagsByTagIds(Collection<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        return problemTagMapper.selectList(
                new LambdaQueryWrapper<ProblemTag>().in(ProblemTag::getTagId, tagIds));
    }
}
