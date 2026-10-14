package top.everblue.oj.problem.application;

import org.springframework.stereotype.Component;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.problem.entity.Problem;
import top.everblue.oj.problem.service.ProblemService;

/**
 * 题目管理访问守卫：统一"教师/管理员 + 题目存在 + 创建人/管理员"三层校验。
 * <p>
 * 由 {@link ProblemManagementApplicationService} 及各拆分子服务共用，
 * 避免 requireManagedProblem 在多处重复实现（拆分前为 4 份私有副本）。
 * </p>
 */
@Component
public class ProblemAccessGuard {

    private final ProblemService problemService;

    public ProblemAccessGuard(ProblemService problemService) {
        this.problemService = problemService;
    }

    /**
     * 校验题目存在且当前用户可管理（教师/管理员 + 创建人或管理员），返回题目实体。
     *
     * @throws IllegalArgumentException 题目不存在或无权管理
     */
    public Problem requireManagedProblem(Long problemId) {
        SecurityUtils.requireTeacherOrAdmin();
        Problem problem = problemService.getById(problemId);
        if (problem == null) {
            throw new IllegalArgumentException("题目不存在");
        }
        SecurityUtils.requireOwnerOrAdmin(problem.getAuthorId());
        return problem;
    }
}
