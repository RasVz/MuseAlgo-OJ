package top.everblue.oj.contest.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.contest.RunningContestQuery;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.entity.ProblemContest;
import top.everblue.oj.contest.mapper.ContestMapper;
import top.everblue.oj.contest.mapper.ProblemContestMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link RunningContestQuery} 的 oj-contest 侧实现。
 * <p>
 * 两段查询：problem_contest 找关联比赛 → contest 按时间窗过滤。
 * AI 答疑入口调用频率低（每次提问一次），且上层有 Redis 短时缓存，不做联表 XML。
 * </p>
 */
@Service
public class RunningContestQueryAdapter implements RunningContestQuery {

    private final ProblemContestMapper problemContestMapper;
    private final ContestMapper contestMapper;

    public RunningContestQueryAdapter(ProblemContestMapper problemContestMapper, ContestMapper contestMapper) {
        this.problemContestMapper = problemContestMapper;
        this.contestMapper = contestMapper;
    }

    @Override
    public boolean hasRunningContestForProblem(Long problemId, LocalDateTime now) {
        if (problemId == null) {
            return false;
        }
        List<Long> contestIds = problemContestMapper.selectList(
                        new LambdaQueryWrapper<ProblemContest>()
                                .eq(ProblemContest::getProblemId, problemId)
                                .select(ProblemContest::getContestId))
                .stream()
                .map(ProblemContest::getContestId)
                .toList();
        if (contestIds.isEmpty()) {
            return false;
        }
        return contestMapper.selectCount(
                new LambdaQueryWrapper<Contest>()
                        .in(Contest::getId, contestIds)
                        .le(Contest::getStartTime, now)
                        .ge(Contest::getEndTime, now)
                        .last("LIMIT 1")) > 0;
    }
}
