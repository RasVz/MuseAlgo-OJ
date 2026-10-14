package top.everblue.oj.judge.infrastructure;

import org.springframework.stereotype.Service;
import top.everblue.oj.api.submission.UserSubmissionMetricsQuery;
import top.everblue.oj.judge.mapper.SubmissionMapper;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 提交数统计适配器：把 oj-judge 的 submission 表聚合成 oj-api 定义的「按用户提交数」能力。
 * <p>
 * 与 {@code UserAiUsageQueryAdapter} 同构——接口由 oj-api 定义、实现放在数据所属模块，
 * 目的是让 oj-api 不反向依赖 oj-judge。这个类的存在只是为了<strong>让依赖方向单向</strong>，
 * 不是为了复用逻辑；真要复用聚合工具，应该在 oj-common 里加而不是复制一份。
 * </p>
 * <p>
 * 入参空集合必须短路：{@code IN ()} 形式的批量统计若放行空集合，会退化成全表 count，
 * 一个空请求就能打出昂贵的聚合查询。
 * </p>
 * <p>
 * 结果用 {@code Map} 一次性返回而不是逐个查询，是为了让调用方在渲染排行榜时只发一批请求。
 * 注意这里返回的是<strong>提交总数</strong>（含失败提交），是否只统计 AC 由调用方按自己的口径处理。
 * </p>
 */
@Service
public class UserSubmissionMetricsQueryAdapter implements UserSubmissionMetricsQuery {

    private final SubmissionMapper submissionMapper;

    public UserSubmissionMetricsQueryAdapter(SubmissionMapper submissionMapper) {
        this.submissionMapper = submissionMapper;
    }

    @Override
    public Map<Long, Long> countByUsers(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return submissionMapper.selectSubmissionCountByUsers(userIds).stream()
                .collect(Collectors.toMap(row -> ((Number) row.get("userId")).longValue(),
                        row -> ((Number) row.get("cnt")).longValue()));
    }
}
