package top.everblue.oj.api.ai;

import java.util.Collection;
import java.util.Map;

/**
 * 用户 AI 用量统计只读契约（管理端/限额展示用）。
 * <p>
 * 实现由 oj-ai 模块提供（数据源为 ai_usage 表），
 * 消费者不直接注入 AI 用量 Mapper。
 * </p>
 */
public interface UserAiUsageQuery {

    /**
     * 批量统计用户的累计 AI token 消耗。
     *
     * @param userIds 用户 ID 集合
     * @return userId → 累计 token 数 映射
     */
    Map<Long, Long> totalTokensByUsers(Collection<Long> userIds);
}
