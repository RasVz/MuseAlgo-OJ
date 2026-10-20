package top.everblue.oj.contest.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import top.everblue.oj.api.contest.ContestProblemAccessQuery;
import top.everblue.oj.api.teaching.ClassQuery;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.entity.ProblemContest;
import top.everblue.oj.contest.mapper.ContestMapper;
import top.everblue.oj.contest.mapper.ProblemContestMapper;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class ContestProblemAccessAdapter implements ContestProblemAccessQuery {
    private final ContestMapper contests;
    private final ProblemContestMapper problems;
    private final ClassQuery classes;

    public boolean canReadContestProblem(Long contestId, Long problemId, Long actor) {
        Contest contest = contests.selectById(contestId);
        if (contest == null || Integer.valueOf(1).equals(contest.getIsDeleted())) return false;
        var relation = problems.selectContestProblemsWithDetails(contestId, null, 0).stream()
                .filter(row -> problemId.equals(row.getProblemId())).findFirst().orElse(null);
        if (relation == null || Integer.valueOf(1).equals(relation.getIsDeleted())) return false;
        if (SecurityUtils.isAdmin() || (SecurityUtils.isTeacherOrAdmin() && actor != null && actor.equals(contest.getAuthorId()))) return true;
        if (!Integer.valueOf(1).equals(contest.getVisible()) || !Integer.valueOf(1).equals(relation.getVisible())
                || contest.getStartTime() == null || LocalDateTime.now().isBefore(contest.getStartTime())) return false;
        // 未实现邀请名单的独立Private比赛只向主办者/管理员开放，不能退化成公开比赛。
        if (contest.getClassId() == null) return actor != null && !"Private".equalsIgnoreCase(contest.getType());
        return actor != null && classes.findById(contest.getClassId())
                .map(c -> (SecurityUtils.isTeacherOrAdmin() && actor.equals(c.teacherId()))
                        || classes.findStudentIds(contest.getClassId()).contains(actor)).orElse(false);
    }

    @Override public boolean canReadProblem(Long problemId, Long actor) {
        return problems.selectList(new LambdaQueryWrapper<ProblemContest>()
                .select(ProblemContest::getContestId)
                .eq(ProblemContest::getProblemId, problemId).eq(ProblemContest::getIsDeleted, 0)).stream()
                .anyMatch(row -> canReadContestProblem(row.getContestId(), problemId, actor));
    }

    @Override public boolean hasRunningContest(Long problemId, Long actor) {
        LocalDateTime now = LocalDateTime.now();
        return problems.selectList(new LambdaQueryWrapper<ProblemContest>()
                .select(ProblemContest::getContestId)
                .eq(ProblemContest::getProblemId, problemId).eq(ProblemContest::getIsDeleted, 0)).stream()
                .map(row -> contests.selectById(row.getContestId())).filter(java.util.Objects::nonNull)
                .anyMatch(c -> !Integer.valueOf(1).equals(c.getIsDeleted()) && c.getStartTime() != null && c.getEndTime() != null
                        && !now.isBefore(c.getStartTime()) && !now.isAfter(c.getEndTime())
                        && canReadContestProblem(c.getId(), problemId, actor));
    }
}
