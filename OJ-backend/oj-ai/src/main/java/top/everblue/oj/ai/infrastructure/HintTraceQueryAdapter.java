package top.everblue.oj.ai.infrastructure;

import org.springframework.stereotype.Service;
import top.everblue.oj.ai.mapper.AiHintTraceMapper;
import top.everblue.oj.api.ai.HintTraceQuery;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Hint Ladder 解锁轨迹只读契约适配器 — oj-ai 侧实现 {@link HintTraceQuery}。
 * <p>
 * 只暴露聚合结果（userId → level / level → 人数），不泄露 {@code AiHintTrace} 实体。
 * </p>
 */
@Service
public class HintTraceQueryAdapter implements HintTraceQuery {

    private final AiHintTraceMapper hintTraceMapper;

    public HintTraceQueryAdapter(AiHintTraceMapper hintTraceMapper) {
        this.hintTraceMapper = hintTraceMapper;
    }

    @Override
    public Map<Long, Integer> maxLevelByProblem(Long problemId, Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> result = new HashMap<>();
        for (Map<String, Object> row : hintTraceMapper.selectMaxLevelByUsers(problemId, userIds)) {
            Long userId = ((Number) row.get("userId")).longValue();
            int maxLevel = ((Number) row.get("maxLevel")).intValue();
            result.put(userId, maxLevel);
        }
        return result;
    }

    @Override
    public Map<Integer, Long> countByLevel(Long problemId) {
        Map<Integer, Long> result = new HashMap<>();
        for (Map<String, Object> row : hintTraceMapper.selectCountByLevel(problemId)) {
            int level = ((Number) row.get("level")).intValue();
            long cnt = ((Number) row.get("cnt")).longValue();
            result.put(level, cnt);
        }
        return result;
    }
}
