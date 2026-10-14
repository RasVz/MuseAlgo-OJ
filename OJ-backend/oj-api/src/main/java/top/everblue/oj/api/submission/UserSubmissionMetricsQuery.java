package top.everblue.oj.api.submission;

import java.util.Collection;
import java.util.Map;

/**
 * 用户提交量统计只读契约（社区/排行榜展示提交数用）。
 * <p>
 * 实现由 oj-judge 模块提供，消费者不直接注入 SubmissionMapper。
 * </p>
 */
public interface UserSubmissionMetricsQuery {

    /**
     * 批量统计用户提交总数。
     *
     * @param userIds 用户 ID 集合
     * @return userId → 提交总数 映射（无提交的用户不出现或计 0 由实现方决定）
     */
    Map<Long, Long> countByUsers(Collection<Long> userIds);
}
