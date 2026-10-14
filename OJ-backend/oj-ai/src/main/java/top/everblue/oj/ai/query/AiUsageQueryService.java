package top.everblue.oj.ai.query;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.everblue.oj.ai.entity.AiUsage;
import top.everblue.oj.ai.mapper.AiUsageMapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * AI 用量只读查询服务（管理台统计/趋势/明细/排行）。
 * <p>
 * Controller 层禁止直注 Mapper，ai_usage 的统计查询统一收口在本服务。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class AiUsageQueryService {

    private final AiUsageMapper aiUsageMapper;

    /** 用量状态汇总（按 status 分组）。 */
    public List<Map<String, Object>> selectStatusSummary(LocalDateTime from, LocalDateTime to) {
        return aiUsageMapper.selectStatusSummary(from, to);
    }

    /** 按日趋势。 */
    public List<Map<String, Object>> selectDailyTrend(LocalDateTime from, LocalDateTime to) {
        return aiUsageMapper.selectDailyTrend(from, to);
    }

    /** 用户 token 消耗排行。 */
    public List<Map<String, Object>> selectUserRanking(LocalDateTime from, LocalDateTime to, int limit) {
        return aiUsageMapper.selectUserRanking(from, to, limit);
    }

    /** 用量明细分页（Wrapper 由 Controller 按筛选条件构造）。 */
    public Page<AiUsage> selectPage(Page<AiUsage> page, Wrapper<AiUsage> wrapper) {
        return aiUsageMapper.selectPage(page, wrapper);
    }

    /** 按用户批量查 token 用量（Dashboard 用）。 */
    public List<Map<String, Object>> selectTokenUsageByUsers(Collection<Long> userIds) {
        return aiUsageMapper.selectTokenUsageByUsers(userIds);
    }
}
