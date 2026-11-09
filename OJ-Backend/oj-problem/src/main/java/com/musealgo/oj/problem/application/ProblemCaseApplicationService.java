package com.musealgo.oj.problem.application;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.problem.error.ProblemErrorCode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.ProblemCase;
import com.musealgo.oj.problem.entity.vo.ProblemCaseVO;
import com.musealgo.oj.problem.importer.FpsTestCase;
import com.musealgo.oj.problem.service.ProblemCaseService;
import com.musealgo.oj.problem.service.ProblemService;
import com.musealgo.oj.problem.service.TestCaseStorageService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 题目私有测试用例管理（ZIP 导入 / JSON 批量保存 / 追加 / 删除 / 清空 / 预览）。
 * <p>
 * 从 {@link ProblemManagementApplicationService} 拆出（2026-08-15 大服务类拆分），
 * 事务由门面方法（原主类同签名方法上的 {@code @Transactional}）保证；
 * 供 FPS 导入链路调用的内部方法（saveImportedCases / updateTestCaseCount）亦公开于此。
 * </p>
 * <p>
 * 本类还负责三件与「用例是判题唯一依据」直接相关的事：
 * </p>
 * <ul>
 *   <li>用例实际变化后让标准答案的「已测试」标记失效（{@code problem.std_tested} 归 0），
 *       避免教师看着「已测试」用一份并未对新数据验证过的 std；无实际变化时不误清。</li>
 *   <li>用例写入提交后失效判题侧进程内用例缓存，避免后续提交在 TTL 内仍按旧用例判题。</li>
 *   <li>用例文件与数据库的顺序保持一致：先写新文件，提交后才删旧文件，回滚时删新文件。</li>
 * </ul>
 * <p>
 * <b>并发约束（2026-10-30）：</b>所有用例写入口都在改动用例行之前先取题目行的写锁
 * （{@link #lockProblemRow}），「测试并保存标准答案」在同一个锁下重算指纹再落库
 * （{@link #lockProblemRowThenFingerprint}）。两边用同一把行锁串行化，
 * 才能保证「比对通过的用例集合」与「写入 std_tested=1 时的用例集合」是同一份数据；
 * 只用一次先比对、后保存的两段式检查，中间仍有窗口。锁只覆盖最后一次比对与写库，
 * Judge0 验证全程不持有事务。
 * </p>
 */
@Service
public class ProblemCaseApplicationService {

    /** 单题测试用例上限（追加、覆盖导入、ZIP 导入统一生效；已有超限数据不删除也不再放宽） */
    public static final int MAX_CASES_PER_PROBLEM = 200;

    /** 普通用例的目标总分：等分后全部用例分值之和固定为该值 */
    public static final int TARGET_TOTAL_SCORE = 100;

    /** 追加保存结果：实际追加数 / 因重复跳过数 / 追加后该题总用例数 / 需提示用户的说明 */
    public record AppendResult(int appended, int skipped, int totalCount, String note) {}

    private final ProblemCaseService problemCaseService;
    private final TestCaseStorageService testCaseStorageService;
    private final ProblemService problemService;
    private final ProblemAccessGuard accessGuard;
    private final ProblemCaseCacheInvalidator caseCacheInvalidator;
    private final JdbcTemplate jdbcTemplate;

    public ProblemCaseApplicationService(ProblemCaseService problemCaseService,
                                         TestCaseStorageService testCaseStorageService,
                                         ProblemService problemService,
                                         ProblemAccessGuard accessGuard,
                                         ProblemCaseCacheInvalidator caseCacheInvalidator,
                                         JdbcTemplate jdbcTemplate) {
        this.problemCaseService = problemCaseService;
        this.testCaseStorageService = testCaseStorageService;
        this.problemService = problemService;
        this.accessGuard = accessGuard;
        this.caseCacheInvalidator = caseCacheInvalidator;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 取题目行的写锁（{@code SELECT ... FOR UPDATE}），必须在改动用例行之前调用。
     * <p>
     * 锁对象是 {@code problem} 行而不是用例行：所有用例写入口本来就要在同一事务里更新
     * {@code problem.test_case_count}、{@code problem.std_tested}，因此这一行天然是「该题用例集合」
     * 的并发汇聚点。统一在这一行上加锁，可以避免以用例行为锁对象时的加锁顺序问题
     * （用例行数量多、插入还会产生间隙锁，两个写入口很容易反向加锁而死锁）。
     * </p>
     * <p>调用方必须处于事务中；无事务时锁随语句结束即释放，等于没加锁。</p>
     */
    public void lockProblemRow(Long problemId) {
        if (problemId == null) {
            return;
        }
        jdbcTemplate.queryForList("SELECT id FROM problem WHERE id = ? FOR UPDATE", Long.class, problemId);
    }

    /**
     * 取题目行写锁后重算用例集合指纹，供「测试并保存标准答案」在落库前做最终一致性校验。
     * <p>
     * 顺序不可颠倒：先加锁再读。加锁保证此后没有任何用例写入口能提交改动；
     * 读必须走锁定读（{@code FOR SHARE}），否则 REPEATABLE READ 会返回事务早期快照，
     * 可能把「已经变化」的用例集判成未变化，重新出现「旧验证结果被标为对新用例已测试」。
     * </p>
     */
    public String lockProblemRowThenFingerprint(Long problemId) {
        lockProblemRow(problemId);
        return CaseSetFingerprint.of(problemCaseService.getProblemCasesForShare(problemId));
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

    /**
     * 当前用例集合指纹（主键 + 顺序 + 分值 + 子任务号）。
     * 仅供测试与「验证开始/结束」的辅助比对；真正的保存前校验必须用
     * {@link #lockProblemRowThenFingerprint}，两段式比对之间存在窗口。
     */
    public String caseFingerprint(Long problemId) {
        return CaseSetFingerprint.of(problemCaseService.getProblemCases(problemId));
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
        lockProblemRow(problemId);
        accessGuard.requireManagedProblem(problemId);
        List<ProblemCase> oldCases = problemCaseService.getProblemCases(problemId);
        List<String> before = caseSignature(oldCases);
        int importedCount = problemCaseService.importCasesFromZip(file, problemId);
        updateTestCaseCount(problemId, importedCount);
        // 覆盖导入：内容确有变化才让「已测试」失效，重复导入同一份数据不误报
        if (!before.equals(caseSignature(problemCaseService.getProblemCases(problemId)))) {
            invalidateStdTested(problemId);
        }
        // 旧文件在新记录提交后才删：事务回滚时旧记录仍在，文件必须还在
        testCaseStorageService.deleteCaseDirsAfterCommit(problemId, caseIdsOf(oldCases));
        evictCasesAfterCommit(problemId);
        return importedCount;
    }

    /**
     * 命题工作台：批量保存测试用例（JSON 直存，覆盖旧用例，与 ZIP 导入语义一致）。
     * 覆盖保存属于用户明确的「重建用例」操作，按普通用例规则等分 100 分。
     */
    @Transactional(rollbackFor = Exception.class)
    public int saveCases(Long problemId, List<FpsTestCase> cases) {
        lockProblemRow(problemId);
        accessGuard.requireManagedProblem(problemId);
        if (cases == null || cases.isEmpty()) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "测试用例不能为空");
        }
        requireWithinCaseLimit(cases.size(), "保存后");
        for (FpsTestCase c : cases) {
            if (c.input() == null || c.output() == null) {
                throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "测试用例内容不完整");
            }
        }
        List<ProblemCase> oldCases = problemCaseService.getProblemCases(problemId);
        List<String> before = caseSignature(oldCases);
        // 先清空该题旧的数据库记录（文件留到提交后删，回滚时旧记录与旧文件都还在）
        problemCaseService.remove(new LambdaQueryWrapper<ProblemCase>()
                .eq(ProblemCase::getProblemId, problemId));
        saveImportedCases(problemId, cases);
        updateTestCaseCount(problemId, cases.size());
        if (!before.equals(caseSignature(problemCaseService.getProblemCases(problemId)))) {
            invalidateStdTested(problemId);
        }
        testCaseStorageService.deleteCaseDirsAfterCommit(problemId, caseIdsOf(oldCases));
        evictCasesAfterCommit(problemId);
        return cases.size();
    }

    /** 删除单个测试用例：删记录 + 提交后删文件，剩余用例重排 case_order，并按原分值语义处理 score。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteCase(Long caseId) {
        if (caseId == null) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "用例ID不能为空");
        }
        ProblemCase pc = problemCaseService.getById(caseId);
        if (pc == null) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_CASE_NOT_FOUND, "测试用例不存在");
        }
        Long problemId = pc.getProblemId();
        lockProblemRow(problemId);
        accessGuard.requireManagedProblem(problemId);
        // 是否普通等分题要在删除前判定：删除后剩余分值本身就不再满足等分规则
        boolean standardScoreSet = isStandardScoreSet(problemCaseService.getProblemCases(problemId));
        problemCaseService.removeById(caseId);
        resequenceCases(problemId, standardScoreSet);
        invalidateStdTested(problemId);
        // 文件在提交后删除：删除失败回滚时用例记录仍然有效，文件不能提前消失
        testCaseStorageService.deleteCaseDirsAfterCommit(problemId, List.of(caseId));
        evictCasesAfterCommit(problemId);
    }

    /** 清空某题全部测试用例（数据库记录 + 计数归零，文件提交后删除）。 */
    @Transactional(rollbackFor = Exception.class)
    public void clearCases(Long problemId) {
        lockProblemRow(problemId);
        accessGuard.requireManagedProblem(problemId);
        List<ProblemCase> existing = problemCaseService.getProblemCases(problemId);
        problemCaseService.remove(new LambdaQueryWrapper<ProblemCase>()
                .eq(ProblemCase::getProblemId, problemId));
        updateTestCaseCount(problemId, 0);
        if (!existing.isEmpty()) {
            invalidateStdTested(problemId);
        }
        testCaseStorageService.deleteCaseDirsAfterCommit(problemId, caseIdsOf(existing));
        evictCasesAfterCommit(problemId);
    }

    /**
     * 剩余用例重排 case_order（从 1 起）并更新总数。
     * <p>
     * 普通等分题按 100 分重新等分（删除后余数按序前移，语义与导入一致）；
     * 自定义分值 / 子任务题只重排顺序、保留原分值，避免静默改写他人设定的评分结构。
     * </p>
     */
    private void resequenceCases(Long problemId, boolean standardScoreSet) {
        List<ProblemCase> rest = problemCaseService.getProblemCases(problemId);
        int total = rest.size();
        int order = 0;
        for (ProblemCase c : rest) {
            order++;
            c.setCaseOrder(order);
            if (standardScoreSet) {
                c.setScore(importedCaseScore(order - 1, total));
            }
            if (!problemCaseService.updateById(c)) {
                throw new BusinessException(ProblemErrorCode.PROBLEM_CASE_WRITE_FAILED, "测试用例更新失败");
            }
        }
        updateTestCaseCount(problemId, total);
    }

    /**
     * AI 题库工具：追加测试数据（不覆盖旧用例）。
     * 按输入内容去重（重复计入 skipped）；case_order 从现有最大续排。
     * <p>
     * 分值规则：普通等分题在追加后按 100 分对全部用例重新等分（与覆盖保存一致）；
     * 自定义分值 / 子任务题保留既有分值，新用例只分配「剩余额度」（100 - 既有总分），
     * 剩余额度不足时新用例记 0 分并在 note 中说明，绝不篡改既有评分结构。
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public AppendResult appendCases(Long problemId, List<FpsTestCase> cases) {
        lockProblemRow(problemId);
        accessGuard.requireManagedProblem(problemId);
        if (cases == null || cases.isEmpty()) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "测试用例不能为空");
        }

        List<ProblemCase> existing = problemCaseService.getProblemCases(problemId);
        boolean standardScoreSet = isStandardScoreSet(existing);
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
                throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "测试用例内容不完整");
            }
            if (!seen.add(normalizeCaseInput(c.input()))) {
                skipped++;
                continue;
            }
            toAdd.add(c);
        }

        int total = existing.size() + toAdd.size();
        if (total > MAX_CASES_PER_PROBLEM) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "追加后测试数据将超过单题上限 "
                    + MAX_CASES_PER_PROBLEM + " 组（现有 " + existing.size() + " 组），"
                    + "请先清理旧用例（覆盖导入或批量保存）后重试");
        }
        if (toAdd.isEmpty()) {
            // 全部重复：用例集合没有变化，不改分值、不失效「已测试」
            return new AppendResult(0, skipped, existing.size(), null);
        }

        Set<Long> createdCaseIds = new HashSet<>();
        testCaseStorageService.deleteCaseDirsOnRollback(problemId, createdCaseIds);

        String note = null;
        int order = maxOrder;
        if (standardScoreSet) {
            for (int i = 0; i < existing.size(); i++) {
                ProblemCase pc = existing.get(i);
                pc.setScore(importedCaseScore(i, total));
                if (!problemCaseService.updateById(pc)) {
                    throw new BusinessException(ProblemErrorCode.PROBLEM_CASE_WRITE_FAILED, "测试用例分值更新失败");
                }
            }
            for (int i = 0; i < toAdd.size(); i++) {
                order++;
                saveNewCase(problemId, toAdd.get(i), order,
                        importedCaseScore(existing.size() + i, total), createdCaseIds);
            }
        } else {
            int used = existing.stream()
                    .mapToInt(pc -> pc.getScore() == null ? 0 : pc.getScore())
                    .sum();
            int remaining = Math.max(0, TARGET_TOTAL_SCORE - used);
            int base = remaining / toAdd.size();
            int extra = remaining % toAdd.size();
            for (int i = 0; i < toAdd.size(); i++) {
                order++;
                saveNewCase(problemId, toAdd.get(i), order, base + (i < extra ? 1 : 0), createdCaseIds);
            }
            note = remaining > 0
                    ? "该题已有自定义分值，已保留原分值；新增用例共同分配剩余 " + remaining + " 分"
                    : "该题既有用例分值已用满 " + TARGET_TOTAL_SCORE + " 分，新增用例记 0 分（仍参与正确性判定）";
        }

        updateTestCaseCount(problemId, total);
        invalidateStdTested(problemId);
        evictCasesAfterCommit(problemId);
        return new AppendResult(toAdd.size(), skipped, total, note);
    }

    /** 落库并写文件一条新用例；写入的目录 ID 记入 createdCaseIds，供事务回滚时清理。 */
    private void saveNewCase(Long problemId, FpsTestCase source, int caseOrder, int score,
                             Set<Long> createdCaseIds) {
        ProblemCase pc = new ProblemCase();
        pc.setProblemId(problemId);
        pc.setCaseOrder(caseOrder);
        pc.setCaseType((byte) 0);
        pc.setStatus((byte) 1);
        pc.setScore(score);
        if (!problemCaseService.save(pc)) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_CASE_WRITE_FAILED, "测试用例保存失败");
        }
        createdCaseIds.add(pc.getId());
        pc.setInputFilePath(testCaseStorageService.writeInput(problemId, pc.getId(), source.input()));
        pc.setOutputFilePath(testCaseStorageService.writeOutput(problemId, pc.getId(), source.output()));
        if (!problemCaseService.updateById(pc)) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_CASE_WRITE_FAILED, "测试用例文件路径保存失败");
        }
    }

    /**
     * 判断是否「普通等分用例集」：顺序连续、子任务一致、分值符合 100/n 的等分规则。
     * 只要存在自定义分值或不同子任务号就视为特殊评分结构，增删时保留原分值。
     */
    static boolean isStandardScoreSet(List<ProblemCase> cases) {
        if (cases == null || cases.isEmpty()) {
            return true;
        }
        Integer subtask = cases.get(0).getSubtaskNo();
        for (int index = 0; index < cases.size(); index++) {
            ProblemCase one = cases.get(index);
            if (!Objects.equals(subtask, one.getSubtaskNo())) {
                return false;
            }
            Integer score = one.getScore();
            if (score == null || score != importedCaseScore(index, cases.size())) {
                return false;
            }
        }
        return true;
    }

    private void requireWithinCaseLimit(int count, String stage) {
        if (count > MAX_CASES_PER_PROBLEM) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, stage + "测试数据超过单题上限 "
                    + MAX_CASES_PER_PROBLEM + " 组（当前 " + count + " 组），请先精简用例后重试");
        }
    }

    /** 用例内容签名：按顺序记录每组的输入/输出，用于判断覆盖写入是否真的改变了数据。 */
    private List<String> caseSignature(List<ProblemCase> cases) {
        List<String> signature = new ArrayList<>(cases.size());
        for (ProblemCase pc : cases) {
            signature.add(readCaseContent(pc.getInputFilePath()) + '\u0000'
                    + readCaseContent(pc.getOutputFilePath()));
        }
        return signature;
    }

    private static List<Long> caseIdsOf(Collection<ProblemCase> cases) {
        List<Long> ids = new ArrayList<>(cases.size());
        for (ProblemCase pc : cases) {
            if (pc.getId() != null) {
                ids.add(pc.getId());
            }
        }
        return ids;
    }

    /**
     * 用例变化后让标准答案的「已测试」失效。
     * <p>
     * 写成无条件的 {@code UPDATE ... WHERE id=? AND std_tested<>0} 而不是「先读再判断再写」：
     * 读是快照读，若另一事务刚把 std_tested 置 1 并提交，本次读到的可能仍是旧值 0，
     * 于是跳过更新，把「已测试」错误地留在新用例上。写语句本身永远基于最新已提交数据，
     * 且与「测试并保存」对同一 problem 行加锁，顺序确定。
     * </p>
     */
    private void invalidateStdTested(Long problemId) {
        problemService.clearStdTested(problemId);
    }

    /**
     * 事务提交后再失效缓存：提交前失效会被并发判题立即回填旧数据（或读到未提交前的快照），
     * 回滚时也会留下一次无意义的失效。
     * <p>
     * 公开供题目删除等同样影响判题数据的入口复用。
     * </p>
     */
    public void evictCasesAfterCommit(Long problemId) {
        if (problemId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    caseCacheInvalidator.evictProblemCases(problemId);
                }
            });
        } else {
            caseCacheInvalidator.evictProblemCases(problemId);
        }
    }

    private static String normalizeCaseInput(String input) {
        return input == null ? "" : input.replace("\r\n", "\n").trim();
    }

    /** 逐条落库导入用例（FPS 导入与覆盖保存共用；调用方事务内执行）。 */
    public void saveImportedCases(Long problemId, List<FpsTestCase> cases) {
        Set<Long> createdCaseIds = new HashSet<>();
        testCaseStorageService.deleteCaseDirsOnRollback(problemId, createdCaseIds);
        for (int index = 0; index < cases.size(); index++) {
            FpsTestCase importedCase = cases.get(index);
            ProblemCase problemCase = new ProblemCase();
            problemCase.setProblemId(problemId);
            problemCase.setCaseOrder(index + 1);
            problemCase.setCaseType((byte) 0);
            problemCase.setStatus((byte) 1);
            problemCase.setScore(importedCaseScore(index, cases.size()));
            if (!problemCaseService.save(problemCase)) {
                throw new BusinessException(ProblemErrorCode.PROBLEM_CASE_WRITE_FAILED, "测试用例保存失败");
            }
            // 先登记再写文件：写文件失败时这一条也要能被回滚清理
            createdCaseIds.add(problemCase.getId());

            problemCase.setInputFilePath(testCaseStorageService.writeInput(
                    problemId, problemCase.getId(), importedCase.input()));
            problemCase.setOutputFilePath(testCaseStorageService.writeOutput(
                    problemId, problemCase.getId(), importedCase.output()));
            if (!problemCaseService.updateById(problemCase)) {
                throw new BusinessException(ProblemErrorCode.PROBLEM_CASE_WRITE_FAILED, "测试用例文件路径保存失败");
            }
        }
    }

    /** 更新题目表 test_case_count（仅改 count 字段，不触及其余字段）。 */
    public void updateTestCaseCount(Long problemId, int testCaseCount) {
        Problem update = new Problem();
        update.setId(problemId);
        update.setTestCaseCount(testCaseCount);
        if (!problemService.updateById(update)) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_CASE_WRITE_FAILED, "测试用例数量更新失败");
        }
    }

    /** 均分分值：如 100 分 3 组 → 34/33/33；用例数小于 1 或序号越界抛异常。 */
    public static int importedCaseScore(int index, int caseCount) {
        if (caseCount <= 0 || index < 0 || index >= caseCount) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "测试用例序号无效");
        }
        return TARGET_TOTAL_SCORE / caseCount + (index < TARGET_TOTAL_SCORE % caseCount ? 1 : 0);
    }
}
