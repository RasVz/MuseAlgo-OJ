package top.everblue.oj.api.ai;

import java.util.Collection;
import java.util.Map;

/**
 * Hint Ladder 解锁轨迹只读契约 — 跨模块读取 {@code ai_hint_trace} 数据的唯一入口。
 * <p>
 * 实现由 oj-ai 模块提供（数据源为 ai_hint_trace 表），
 * 教师端学情、掌握度算法等消费者不直接注入 AI 模块 Mapper。
 * </p>
 */
public interface HintTraceQuery {

    /**
     * 按题目批量查询一组用户的最高解锁级别。
     *
     * @param problemId 题目 ID
     * @param userIds   用户 ID 集合（空集合返回空映射）
     * @return userId → 最高解锁级别（1-4）映射；无轨迹的用户不出现在结果中
     */
    Map<Long, Integer> maxLevelByProblem(Long problemId, Collection<Long> userIds);

    /**
     * 按题目统计各级别解锁人数。
     *
     * @param problemId 题目 ID
     * @return level → 解锁到该级别的用户数 映射
     */
    Map<Integer, Long> countByLevel(Long problemId);
}
