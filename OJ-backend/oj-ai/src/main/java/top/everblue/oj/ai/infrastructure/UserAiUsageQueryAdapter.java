package top.everblue.oj.ai.infrastructure;

import org.springframework.stereotype.Service;
import top.everblue.oj.ai.mapper.AiUsageMapper;
import top.everblue.oj.api.ai.UserAiUsageQuery;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 用量查询的出参适配器：把 oj-ai 内部的 {@link AiUsageMapper} 结果转成 oj-api 定义的接口契约。
 * <p>
 * 存在的意义是<strong>倒置依赖方向</strong>：oj-api 只需要「按用户聚合 token 数」这一个能力，
 * 由它自己定义接口（{@link top.everblue.oj.api.ai.UserAiUsageQuery}），
 * 由 oj-ai 提供实现。这样 oj-api 不必反向依赖 oj-ai，模块依赖图保持单向。
 * </p>
 * <p>
 * 适配层把 {@code Map} 结果（带类型擦除，取出的是 {@code Object}）转成 {@code Map<Long, Long>}，
 * 调用方不必每次都记得强转、也不必处理 {@code null} 元素。
 * </p>
 * <p>
 * 入参为空的短路是必要的：本方法按用户集合批量查，集合为空时仍发 SQL 会被数据库当成
 * 无条件全表聚合，属于典型的「一个空请求打穿数据库」场景。
 * </p>
 */
@Service
public class UserAiUsageQueryAdapter implements UserAiUsageQuery {

    private final AiUsageMapper aiUsageMapper;

    public UserAiUsageQueryAdapter(AiUsageMapper aiUsageMapper) {
        this.aiUsageMapper = aiUsageMapper;
    }

    @Override
    public Map<Long, Long> totalTokensByUsers(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return aiUsageMapper.selectTokenUsageByUsers(userIds).stream()
                .collect(Collectors.toMap(row -> ((Number) row.get("userId")).longValue(),
                        row -> ((Number) row.get("tokens")).longValue()));
    }
}
