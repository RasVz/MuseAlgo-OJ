package com.musealgo.oj.api.problem;

import java.util.List;

/**
 * 题面生成草稿落库契约。
 * <p>
 * 生成草稿属于 AI 产物，由 {@code oj-ai} 持有；写题库需要题目域的校验、消毒、标签关联与索引，
 * 因此通过本接口反向调用 {@code oj-problem}，避免 {@code oj-ai} 直接依赖 {@code oj-problem} 造成模块环。
 * </p>
 * <p>
 * 实现方必须遵守既有落库规则：正文按 {@code StatementSanitizer} 消毒、只写入可编辑字段、
 * 新建题目默认私有（{@code is_public=0}），不得因为调用方是 AI 流程而放宽。
 * </p>
 */
public interface ProblemDraftPublisher {

    /**
     * 由草稿内容新建一道私有题目。
     *
     * @param actorId 归属教师，写入 {@code problem.author_id}
     * @return 新题目 ID
     */
    Long createDraft(Long actorId, DraftProblemCommand command);

    /**
     * 标题相近的历史题目，用于提示「可能重复出题」。
     * <p>按标题 3-gram Jaccard 相似度排序；候选集为全库未删除题目，允许实现方做短时缓存。</p>
     *
     * @param actorId 调用者；只能看到公开题与自己出过的私有题，不能借相似度接口探测他人私有题目
     * @param title   待比较标题
     * @param limit   最多返回条数，调用方保证 1~10
     */
    List<SimilarProblemView> findSimilar(Long actorId, String title, int limit);
}
