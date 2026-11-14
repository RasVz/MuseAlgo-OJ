package com.musealgo.oj.contest.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.musealgo.oj.api.contest.ContestProblemAccessQuery;
import com.musealgo.oj.api.teaching.ClassQuery;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.contest.entity.Contest;
import com.musealgo.oj.contest.entity.ProblemContest;
import com.musealgo.oj.contest.mapper.ContestMapper;
import com.musealgo.oj.contest.mapper.ProblemContestMapper;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

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

    @Override public boolean hasRunningFormalContestProblem(Long problemId) {
        return problemId != null && findRunningFormalContestProblemIds().contains(problemId);
    }

    @Override public boolean canAccessContest(Long contestId, Long actor) {
        Contest contest = contests.selectById(contestId);
        if (contest == null || Integer.valueOf(1).equals(contest.getIsDeleted())) return false;
        if (SecurityUtils.isAdmin()
                || (SecurityUtils.isTeacherOrAdmin() && actor != null && actor.equals(contest.getAuthorId()))) return true;
        if (!Integer.valueOf(1).equals(contest.getVisible())) return false;
        if (contest.getClassId() == null) return actor != null && !"Private".equalsIgnoreCase(contest.getType());
        return actor != null && classes.findById(contest.getClassId())
                .map(c -> (SecurityUtils.isTeacherOrAdmin() && actor.equals(c.teacherId()))
                        || classes.findStudentIds(contest.getClassId()).contains(actor)).orElse(false);
    }

    /**
     * 取「公开且正在进行」的比赛题目 ID 集合。
     * <p>未公开比赛可能仍在配置中，不能用来阻断题目在题库中的正常练习；只封锁已公开比赛的赛程窗口。</p>
     */
    @Override public Set<Long> findRunningFormalContestProblemIds() {
        LocalDateTime now = LocalDateTime.now();
        Set<Long> runningContestIds = contests.selectList(new LambdaQueryWrapper<Contest>()
                        .select(Contest::getId)
                        .eq(Contest::getVisible, 1)
                        .eq(Contest::getIsDeleted, 0)
                        .le(Contest::getStartTime, now)
                        .ge(Contest::getEndTime, now))
                .stream().map(Contest::getId).collect(Collectors.toSet());
        if (runningContestIds.isEmpty()) return Set.of();
        return problems.selectList(new LambdaQueryWrapper<ProblemContest>()
                        .select(ProblemContest::getProblemId)
                        .in(ProblemContest::getContestId, runningContestIds)
                        .eq(ProblemContest::getIsDeleted, 0)
                        .eq(ProblemContest::getVisible, 1))
                .stream().map(ProblemContest::getProblemId).collect(Collectors.toSet());
    }
}
