package top.everblue.oj.problem.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.problem.entity.Problem;
import top.everblue.oj.problem.service.ProblemService;

import java.util.HashMap;
import java.util.Map;

/**
 * 标准答案（std）管理：保存（强制/测试后）/ 读取 / 清除 / 保存并清空用例。
 * <p>
 * 从 {@link ProblemManagementApplicationService} 拆出（2026-08-15 大服务类拆分）。
 * </p>
 */
@Service
public class ProblemStdApplicationService {

    private final ProblemService problemService;
    private final ProblemAccessGuard accessGuard;
    private final ProblemCaseApplicationService caseApplicationService;

    public ProblemStdApplicationService(ProblemService problemService,
                                        ProblemAccessGuard accessGuard,
                                        ProblemCaseApplicationService caseApplicationService) {
        this.problemService = problemService;
        this.accessGuard = accessGuard;
        this.caseApplicationService = caseApplicationService;
    }

    /**
     * 强制保存标准答案：未经过测试数据验证，标记 std_tested=0。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveStd(Long problemId, String code, String explanation) {
        saveStdInternal(problemId, code, explanation, 0);
    }

    /** 测试并保存标准答案：已通过全部测试数据验证，标记 std_tested=1。 */
    @Transactional(rollbackFor = Exception.class)
    public void saveStdTested(Long problemId, String code, String explanation) {
        saveStdInternal(problemId, code, explanation, 1);
    }

    private void saveStdInternal(Long problemId, String code, String explanation, int tested) {
        Problem problem = accessGuard.requireManagedProblem(problemId);
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("标准答案代码不能为空");
        }
        problem.setStdAnswer(code);
        problem.setStdExplanation(explanation == null ? "" : explanation);
        problem.setStdTested(tested);
        if (!problemService.updateById(problem)) {
            throw new IllegalStateException("标准答案保存失败");
        }
    }

    /** AI 题库工具：读取标准答案（仅教师/管理员）。 */
    public Map<String, Object> getStd(Long problemId) {
        Problem problem = accessGuard.requireManagedProblem(problemId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", problem.getStdAnswer() == null ? "" : problem.getStdAnswer());
        result.put("explanation", problem.getStdExplanation() == null ? "" : problem.getStdExplanation());
        result.put("tested", problem.getStdTested() != null && problem.getStdTested() == 1);
        return result;
    }

    /** AI 题库工具：清除标准答案（仅教师/管理员）。 */
    @Transactional(rollbackFor = Exception.class)
    public void clearStd(Long problemId) {
        Problem problem = accessGuard.requireManagedProblem(problemId);
        problem.setStdAnswer(null);
        problem.setStdExplanation(null);
        if (!problemService.updateById(problem)) {
            throw new IllegalStateException("标准答案清除失败");
        }
    }

    /**
     * 标准答案管理：保存 std 并清空该题全部测试用例。
     * <p>
     * 用于修复「原 std 错误 → 基于错 std 生成的测试数据也错误」的场景：
     * 保存修正后的 std 同时清掉旧测试数据，之后需重新生成。
     * 清空用例委托 {@link ProblemCaseApplicationService#clearCases}（同一事务内执行）。
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveStdAndClearCases(Long problemId, String code, String explanation) {
        Problem problem = accessGuard.requireManagedProblem(problemId);
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("标准答案代码不能为空");
        }
        problem.setStdAnswer(code);
        problem.setStdExplanation(explanation == null ? "" : explanation);
        problem.setStdTested(0); // 清空用例后未验证，标记未测试
        if (!problemService.updateById(problem)) {
            throw new IllegalStateException("标准答案保存失败");
        }
        caseApplicationService.clearCases(problemId);
    }
}
