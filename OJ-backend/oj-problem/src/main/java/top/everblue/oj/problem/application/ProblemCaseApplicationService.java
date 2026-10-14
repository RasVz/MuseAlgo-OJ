package top.everblue.oj.problem.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import top.everblue.oj.problem.entity.Problem;
import top.everblue.oj.problem.entity.ProblemCase;
import top.everblue.oj.problem.entity.vo.ProblemCaseVO;
import top.everblue.oj.problem.importer.FpsTestCase;
import top.everblue.oj.problem.service.ProblemCaseService;
import top.everblue.oj.problem.service.ProblemService;
import top.everblue.oj.problem.service.TestCaseStorageService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 题目私有测试用例管理（ZIP 导入 / JSON 批量保存 / 追加 / 删除 / 清空 / 预览）。
 * <p>
 * 从 {@link ProblemManagementApplicationService} 拆出（2026-08-15 大服务类拆分），
 * 事务由门面方法（原主类同签名方法上的 {@code @Transactional}）保证；
 * 供 FPS 导入链路调用的内部方法（saveImportedCases / updateTestCaseCount）亦公开于此。
 * </p>
 */
@Service
public class ProblemCaseApplicationService {

    /** 追加保存测试用例的单题上限（含现有用例） */
    public static final int MAX_CASES_PER_PROBLEM = 200;

    /** 追加保存结果：实际追加数 / 因重复跳过数 / 追加后该题总用例数 */
    public record AppendResult(int appended, int skipped, int totalCount) {}

    private final ProblemCaseService problemCaseService;
    private final TestCaseStorageService testCaseStorageService;
    private final ProblemService problemService;
    private final ProblemAccessGuard accessGuard;

    public ProblemCaseApplicationService(ProblemCaseService problemCaseService,
                                         TestCaseStorageService testCaseStorageService,
                                         ProblemService problemService,
                                         ProblemAccessGuard accessGuard) {
        this.problemCaseService = problemCaseService;
        this.testCaseStorageService = testCaseStorageService;
        this.problemService = problemService;
        this.accessGuard = accessGuard;
    }

    /**
     * 读取该题全部测试用例（含输入/输出全文，超长截断，供管理端预览）。
     */
    public List<ProblemCaseVO> getPrivateCases(Long problemId) {
        accessGuard.requireManagedProblem(problemId);
        List<ProblemCase> list = problemCaseService.getProblemCases(problemId);
        List<ProblemCaseVO> result = new ArrayList<>(list.size());
        for (ProblemCase pc : list) {
            ProblemCaseVO vo = new ProblemCaseVO();
            org.springframework.beans.BeanUtils.copyProperties(pc, vo);
            vo.setInput(ProblemCaseVO.truncate(readCaseContent(pc.getInputFilePath())));
            vo.setOutput(ProblemCaseVO.truncate(readCaseContent(pc.getOutputFilePath())));
            result.add(vo);
        }
        return result;
    }

    /** 读取用例文件内容；文件缺失等异常返回空串（预览场景不阻断列表）。 */
    private String readCaseContent(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "";
        }
        try {
            return testCaseStorageService.readByRelativePath(relativePath);
        } catch (Exception e) {
            return "";
        }
    }

    /** ZIP 导入测试用例：导入后按导入数量更新总条数。 */
    @Transactional(rollbackFor = Exception.class)
    public int importCases(Long problemId, MultipartFile file) {
        accessGuard.requireManagedProblem(problemId);
        int importedCount = problemCaseService.importCasesFromZip(file, problemId);
        updateTestCaseCount(problemId, importedCount);
        return importedCount;
    }

    /**
     * 命题工作台：批量保存测试用例（JSON 直存，覆盖旧用例，与 ZIP 导入语义一致）。
     */
    @Transactional(rollbackFor = Exception.class)
    public int saveCases(Long problemId, List<FpsTestCase> cases) {
        accessGuard.requireManagedProblem(problemId);
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("测试用例不能为空");
        }
        for (FpsTestCase c : cases) {
            if (c.input() == null || c.output() == null) {
                throw new IllegalArgumentException("测试用例内容不完整");
            }
        }
        // 先清空该题旧的测试用例文件与数据库记录
        testCaseStorageService.deleteByProblemId(problemId);
        problemCaseService.remove(new LambdaQueryWrapper<ProblemCase>()
                .eq(ProblemCase::getProblemId, problemId));
        saveImportedCases(problemId, cases);
        updateTestCaseCount(problemId, cases.size());
        return cases.size();
    }

    /** 删除单个测试用例：删文件 + 删记录，剩余用例重排 case_order 并重算 score。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteCase(Long caseId) {
        if (caseId == null) {
            throw new IllegalArgumentException("用例ID不能为空");
        }
        ProblemCase pc = problemCaseService.getById(caseId);
        if (pc == null) {
            throw new IllegalArgumentException("测试用例不存在");
        }
        Long problemId = pc.getProblemId();
        accessGuard.requireManagedProblem(problemId);
        testCaseStorageService.deleteCase(problemId, pc.getId());
        problemCaseService.removeById(caseId);
        resequenceCases(problemId);
    }

    /** 清空某题全部测试用例（文件 + 记录 + 计数归零）。 */
    @Transactional(rollbackFor = Exception.class)
    public void clearCases(Long problemId) {
        accessGuard.requireManagedProblem(problemId);
        testCaseStorageService.deleteByProblemId(problemId);
        problemCaseService.remove(new LambdaQueryWrapper<ProblemCase>()
                .eq(ProblemCase::getProblemId, problemId));
        updateTestCaseCount(problemId, 0);
    }

    /** 剩余用例重排 case_order（从 1 起）并按均分语义重算 score、更新总数。 */
    private void resequenceCases(Long problemId) {
        List<ProblemCase> rest = problemCaseService.getProblemCases(problemId);
        int total = rest.size();
        int order = 0;
        for (ProblemCase c : rest) {
            order++;
            c.setCaseOrder(order);
            c.setScore(importedCaseScore(order - 1, total));
            if (!problemCaseService.updateById(c)) {
                throw new IllegalStateException("测试用例更新失败");
            }
        }
        updateTestCaseCount(problemId, total);
    }

    /**
     * AI 题库工具：追加测试数据（不覆盖旧用例）。
     * 按输入内容去重（重复计入 skipped）；case_order 从现有最大续排；
     * 追加后重新均分全部用例分值（与覆盖保存的均分语义一致）并更新总条数。
     */
    @Transactional(rollbackFor = Exception.class)
    public AppendResult appendCases(Long problemId, List<FpsTestCase> cases) {
        accessGuard.requireManagedProblem(problemId);
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("测试用例不能为空");
        }

        List<ProblemCase> existing = problemCaseService.getProblemCases(problemId);
        Set<String> seen = new HashSet<>();
        int maxOrder = 0;
        for (ProblemCase pc : existing) {
            if (pc.getCaseOrder() != null) {
                maxOrder = Math.max(maxOrder, pc.getCaseOrder());
            }
            String input = pc.getInputFilePath() == null ? null
                    : testCaseStorageService.readByRelativePath(pc.getInputFilePath());
            if (input != null) {
                seen.add(normalizeCaseInput(input));
            }
        }

        List<FpsTestCase> toAdd = new ArrayList<>();
        int skipped = 0;
        for (FpsTestCase c : cases) {
            if (c.input() == null || c.output() == null) {
                throw new IllegalArgumentException("测试用例内容不完整");
            }
            if (!seen.add(normalizeCaseInput(c.input()))) {
                skipped++;
                continue;
            }
            toAdd.add(c);
        }

        int total = existing.size() + toAdd.size();
        if (total > MAX_CASES_PER_PROBLEM) {
            throw new IllegalArgumentException("追加后测试数据将超过单题上限 "
                    + MAX_CASES_PER_PROBLEM + " 组（现有 " + existing.size() + " 组），"
                    + "请先清理旧用例（覆盖导入或批量保存）后重试");
        }
        if (toAdd.isEmpty()) {
            return new AppendResult(0, skipped, existing.size());
        }

        // 重新均分全部用例分值（与覆盖保存 importedCaseScore 语义一致）
        int order = 0;
        for (ProblemCase pc : existing) {
            order++;
            pc.setScore(importedCaseScore(order - 1, total));
            if (!problemCaseService.updateById(pc)) {
                throw new IllegalStateException("测试用例分值更新失败");
            }
        }
        for (FpsTestCase c : toAdd) {
            order++;
            ProblemCase pc = new ProblemCase();
            pc.setProblemId(problemId);
            pc.setCaseOrder(order);
            pc.setCaseType((byte) 0);
            pc.setStatus((byte) 1);
            pc.setScore(importedCaseScore(order - 1, total));
            if (!problemCaseService.save(pc)) {
                throw new IllegalStateException("测试用例保存失败");
            }
            pc.setInputFilePath(testCaseStorageService.writeInput(problemId, pc.getId(), c.input()));
            pc.setOutputFilePath(testCaseStorageService.writeOutput(problemId, pc.getId(), c.output()));
            if (!problemCaseService.updateById(pc)) {
                throw new IllegalStateException("测试用例文件路径保存失败");
            }
        }

        updateTestCaseCount(problemId, total);
        return new AppendResult(toAdd.size(), skipped, total);
    }

    private static String normalizeCaseInput(String input) {
        return input == null ? "" : input.replace("\r\n", "\n").trim();
    }

    /** 逐条落库导入用例（FPS 导入与覆盖保存共用；调用方事务内执行）。 */
    public void saveImportedCases(Long problemId, List<FpsTestCase> cases) {
        for (int index = 0; index < cases.size(); index++) {
            FpsTestCase importedCase = cases.get(index);
            ProblemCase problemCase = new ProblemCase();
            problemCase.setProblemId(problemId);
            problemCase.setCaseOrder(index + 1);
            problemCase.setCaseType((byte) 0);
            problemCase.setStatus((byte) 1);
            problemCase.setScore(importedCaseScore(index, cases.size()));
            if (!problemCaseService.save(problemCase)) {
                throw new IllegalStateException("测试用例保存失败");
            }

            problemCase.setInputFilePath(testCaseStorageService.writeInput(
                    problemId, problemCase.getId(), importedCase.input()));
            problemCase.setOutputFilePath(testCaseStorageService.writeOutput(
                    problemId, problemCase.getId(), importedCase.output()));
            if (!problemCaseService.updateById(problemCase)) {
                throw new IllegalStateException("测试用例文件路径保存失败");
            }
        }
    }

    /** 更新题目表 test_case_count（仅改 count 字段，不触及其余字段）。 */
    public void updateTestCaseCount(Long problemId, int testCaseCount) {
        Problem update = new Problem();
        update.setId(problemId);
        update.setTestCaseCount(testCaseCount);
        if (!problemService.updateById(update)) {
            throw new IllegalStateException("测试用例数量更新失败");
        }
    }

    /** 均分分值：如 100 分 3 组 → 34/33/33；用例数小于 1 或序号越界抛异常。 */
    static int importedCaseScore(int index, int caseCount) {
        if (caseCount <= 0 || index < 0 || index >= caseCount) {
            throw new IllegalArgumentException("测试用例序号无效");
        }
        return 100 / caseCount + (index < 100 % caseCount ? 1 : 0);
    }
}
