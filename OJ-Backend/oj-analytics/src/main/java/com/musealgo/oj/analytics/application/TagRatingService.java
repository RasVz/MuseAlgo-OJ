package com.musealgo.oj.analytics.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.analytics.entity.ProblemTag;
import com.musealgo.oj.analytics.entity.Tag;
import com.musealgo.oj.analytics.entity.UserTagRating;
import com.musealgo.oj.analytics.mapper.ProblemTagMapper;
import com.musealgo.oj.analytics.mapper.TagMapper;
import com.musealgo.oj.analytics.mapper.UserTagRatingMapper;
import com.musealgo.oj.api.problem.ProblemDigest;
import com.musealgo.oj.api.problem.ProblemQuery;
import com.musealgo.oj.api.submission.SubmissionQuery;

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
    /**
     * AC 提交流水（oj-api 契约，oj-judge 实现）。
     * <p>
     * 只依赖 oj-api 契约、不注入 oj-judge 的 Mapper/实现：oj-analytics 与 oj-judge 是
     * 平行业务模块，跨模块数据访问必须走契约（架构守卫 ModuleDependencyArchTest 强制）。
     * </p>
     */
    private final SubmissionQuery submissionQuery;

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
            List<com.musealgo.oj.analytics.entity.UserTagRating> existing =
                    userTagRatingMapper.selectByUserAndTags(userId, List.of(tagId));
            int current = existing.isEmpty() ? DEFAULT_RATING
                    : existing.get(0).getRating() != null ? existing.get(0).getRating() : DEFAULT_RATING;

            int gain = calcGain(current, difficultyRating);
            int newRating = Math.min(current + gain, difficultyRating + CAP_MARGIN);

            userTagRatingMapper.upsert(userId, tagId, newRating);
            log.debug("TagRating user={} tag={} {}→{} gain={}", userId, tagId, current, newRating, gain);
        }
    }

    /**
     * 重建某用户的全部标签 ELO 评分（T8 受控重判后的派生数据修复）。
     * <p>
     * {@code user_tag_rating} 是「按 AC 事件顺序累计」的派生缓存：rating 只升不降、
     * problem_count 只增，单条 upsert 无法回退旧结论。因此重判改变提交 status 后，
     * 必须先删除该用户全部评分，再按 AC 提交的 (create_time, id) 历史顺序逐条重放
     * {@link #updateRating(Long, Long)}，才能得到与原始判题链路一致的终态。
     * </p>
     * <p>
     * 仅由 T8 重判后处理（{@code RejudgePostProcessListener}）调用，不用于日常 AC 增量更新
     * （日常增量走 MQ 的 updateRating）。整段在一个事务内：删除或重放中途失败时回滚到
     * 重建前的状态，由 T8-2 的重试/对账再次触发；不会留下「删了一半评分」的中间态。
     * </p>
     * <p>
     * 事务传播必须是 {@link Propagation#REQUIRES_NEW}：调用方是
     * {@code @TransactionalEventListener(AFTER_COMMIT)}，此时原事务<strong>已提交但资源仍绑定</strong>，
     * 默认 REQUIRED 会「加入」这个已结束的事务，写入不会再有 commit，连接归还池时被回滚
     * （Spring 文档对 afterCommit 的明确要求）。独立新事务同时保证重建失败只回滚自己的改动。
     * </p>
     *
     * @param userId 目标用户 ID；为 null 时静默返回
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rebuildForUser(Long userId) {
        if (userId == null) {
            return;
        }
        // 先取数再删除：查询失败时不破坏现有评分；删除本身在事务内，后续重放失败会回滚
        List<SubmissionQuery.AcceptedSubmission> accepted =
                submissionQuery.findAcceptedChronological(userId);
        userTagRatingMapper.deleteByUser(userId);
        for (SubmissionQuery.AcceptedSubmission submission : accepted) {
            updateRating(userId, submission.problemId());
        }
        log.info("TagRating 重建完成 userId={} acceptedSubmissions={}", userId, accepted.size());
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
