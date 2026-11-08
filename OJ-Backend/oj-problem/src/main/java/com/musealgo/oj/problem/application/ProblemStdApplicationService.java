package com.musealgo.oj.problem.application;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.problem.error.ProblemErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.service.ProblemService;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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

    /**
     * 测试并保存标准答案：已通过全部测试数据验证，标记 std_tested=1。
     * <p>
     * <b>为什么需要 {@code expectFingerprint}</b>：Judge0 验证一次要几十秒，
     * 期间别的窗口可能已经改/删/清掉用例。只在验证前后各比一次指纹是不够的——
     * 比对通过到真正写库之间仍有窗口。这里把「取题目写锁 → 重算指纹 → 比对 → 写库」
     * 放在同一个事务里，用例写入口也都在改用例前取同一把锁，因此写库时的用例集合
     * 必然就是刚才比对通过的那一份。
     * </p>
     *
     * @param expectFingerprint 验证开始时（读取用例内容之前）记录的集合指纹
     * @throws CaseSetChangedException 验证期间用例集合已变化，本次结果不能代表当前数据
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveStdTested(Long problemId, String code, String explanation, String expectFingerprint) {
        if (code == null || code.trim().isEmpty()) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "标准答案代码不能为空");
        }
        // 先加锁再读：加锁后不会再有用例写入口提交改动，锁定读保证读到的是最新已提交数据
        String currentFingerprint = caseApplicationService.lockProblemRowThenFingerprint(problemId);
        if (!Objects.equals(expectFingerprint, currentFingerprint)) {
            throw new CaseSetChangedException(
                    "验证期间测试用例已被修改，本次结果不能代表当前用例，未保存，请重新测试");
        }
        Problem problem = accessGuard.requireManagedProblem(problemId);
        // 只写 std 三个字段：整实体回写会把加锁前读到的 test_case_count 等字段一起写回，
        // 覆盖掉并发用例写入刚提交的计数
        if (!problemService.updateStd(problem.getId(), code,
                explanation == null ? "" : explanation, 1)) {
            throw new IllegalStateException("标准答案保存失败");
        }
    }

    private void saveStdInternal(Long problemId, String code, String explanation, int tested) {
        accessGuard.requireManagedProblem(problemId);
        if (code == null || code.trim().isEmpty()) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "标准答案代码不能为空");
        }
        if (!problemService.updateStd(problemId, code, explanation == null ? "" : explanation, tested)) {
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
        accessGuard.requireManagedProblem(problemId);
        // 代码已清空后「已测试」不再有意义，一并归零，避免界面出现「已测试但没有代码」
        if (!problemService.updateStd(problemId, null, null, 0)) {
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
        // 与用例写入统一加锁顺序：先题目行，再动用例
        caseApplicationService.lockProblemRow(problemId);
        accessGuard.requireManagedProblem(problemId);
        if (code == null || code.trim().isEmpty()) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "标准答案代码不能为空");
        }
        if (!problemService.updateStd(problemId, code, explanation == null ? "" : explanation, 0)) {
            throw new IllegalStateException("标准答案保存失败");
        }
        caseApplicationService.clearCases(problemId);
    }
}
