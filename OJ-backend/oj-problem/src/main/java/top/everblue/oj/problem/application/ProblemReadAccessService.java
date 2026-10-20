package top.everblue.oj.problem.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.problem.ProblemAccessQuery;
import top.everblue.oj.api.contest.ContestProblemAccessQuery;
import top.everblue.oj.api.teaching.ClassQuery;
import top.everblue.oj.common.exception.ForbiddenException;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.problem.entity.Problem;
import top.everblue.oj.problem.mapper.ProblemListItemMapper;
import top.everblue.oj.problem.service.ProblemService;

@Service
@RequiredArgsConstructor
public class ProblemReadAccessService implements ProblemAccessQuery {
    private final ProblemService problems;
    private final ProblemListItemMapper lists;
    private final ClassQuery classes;
    private final ContestProblemAccessQuery contests;

    private Problem requireExisting(Long id) {
        Problem problem = problems.getById(id);
        if (problem == null || Integer.valueOf(1).equals(problem.getIsDeleted())) throw new IllegalArgumentException("题目不存在");
        return problem;
    }

    @Override public void requireReadable(Long id) {
        Problem problem = requireExisting(id);
        Long actor = SecurityUtils.getUserId();
        if (SecurityUtils.isAdmin() || (actor != null && actor.equals(problem.getAuthorId())) || Boolean.TRUE.equals(problem.getIsPublic())) return;
        if (actor != null && (contests.canReadProblem(id, actor) || lists.selectAssignedClassIds(id).stream().anyMatch(classId ->
                classes.findById(classId).map(c -> (SecurityUtils.isTeacherOrAdmin() && actor.equals(c.teacherId())) || classes.findStudentIds(classId).contains(actor)).orElse(false)))) return;
        throw new ForbiddenException("无权查看该题目");
    }

    @Override public void requirePracticeSubmission(Long id) {
        requireReadable(id);
        Problem problem = requireExisting(id);
        Long actor = SecurityUtils.getUserId();
        if (!SecurityUtils.isAdmin() && !java.util.Objects.equals(actor, problem.getAuthorId()) && contests.hasRunningContest(id, actor))
            throw new ForbiddenException("比赛进行中，请从比赛入口提交");
    }
}
