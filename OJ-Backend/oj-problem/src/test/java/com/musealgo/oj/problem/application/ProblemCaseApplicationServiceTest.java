package com.musealgo.oj.problem.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.ProblemCase;
import com.musealgo.oj.problem.importer.FpsTestCase;
import com.musealgo.oj.problem.service.ProblemCaseService;
import com.musealgo.oj.problem.service.ProblemService;
import com.musealgo.oj.problem.service.TestCaseStorageService;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 用例增删改的分值语义、标准答案「已测试」失效、判题缓存失效，以及并发相关加锁顺序。
 * <p>
 * 断言的是业务结果（分数、状态标记、是否误清、加锁是否早于改数据），不是「方法被调用」。
 * 真正的并发正确性由真实 MySQL 的 {@code ProblemCaseConsistencyIT} 用受控交错证明。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ProblemCaseApplicationServiceTest {

    private static final Long PROBLEM_ID = 42L;

    @Mock
    private ProblemCaseService problemCaseService;
    @Mock
    private TestCaseStorageService testCaseStorageService;
    @Mock
    private ProblemService problemService;
    @Mock
    private ProblemAccessGuard accessGuard;
    @Mock
    private ProblemCaseCacheInvalidator caseCacheInvalidator;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private ProblemCaseApplicationService service;
    private Problem problem;

    @BeforeEach
    void setUp() {
        service = new ProblemCaseApplicationService(problemCaseService, testCaseStorageService,
                problemService, accessGuard, caseCacheInvalidator, jdbcTemplate);
        problem = new Problem();
        problem.setId(PROBLEM_ID);
        problem.setStdTested(1);
        // 持久层默认返回 true（Mockito 对 boolean 默认 false，会让服务误判写库失败）
        lenient().when(problemCaseService.updateById(any(ProblemCase.class))).thenReturn(true);
        lenient().when(problemService.updateById(any(Problem.class))).thenReturn(true);
    }

    /** 构造一条用例：id 从 1 开始，子任务默认 1。 */
    private static ProblemCase caseOf(long id, int order, int score) {
        ProblemCase one = new ProblemCase();
        one.setId(id);
        one.setProblemId(PROBLEM_ID);
        one.setCaseOrder(order);
        one.setScore(score);
        one.setSubtaskNo(1);
        return one;
    }

    private static List<ProblemCase> standardSet(int count) {
        List<ProblemCase> cases = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            cases.add(caseOf(i + 1L, i + 1, ProblemCaseApplicationService.importedCaseScore(i, count)));
        }
        return cases;
    }

    @Test
    void deleteOnStandardSetResplitsRemainingScoresAndInvalidatesTestedStd() {
        List<ProblemCase> before = standardSet(3); // 34/33/33
        ProblemCase removed = before.get(1);
        when(problemCaseService.getById(2L)).thenReturn(removed);
        when(accessGuard.requireManagedProblem(PROBLEM_ID)).thenReturn(problem);
        when(problemCaseService.getProblemCases(PROBLEM_ID))
                .thenReturn(before)                       // 删除前判定是否普通等分
                .thenReturn(List.of(before.get(0), before.get(2))); // 删除后重排

        service.deleteCase(2L);

        ArgumentCaptor<ProblemCase> updated = ArgumentCaptor.forClass(ProblemCase.class);
        verify(problemCaseService, times(2)).updateById(updated.capture());
        assertThat(updated.getAllValues())
                .extracting(ProblemCase::getScore, ProblemCase::getCaseOrder)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(50, 1),
                        org.assertj.core.groups.Tuple.tuple(50, 2));

        // 计数更新一次，且「已测试」失效走的是无条件 UPDATE
        ArgumentCaptor<Problem> problemUpdate = ArgumentCaptor.forClass(Problem.class);
        verify(problemService, times(1)).updateById(problemUpdate.capture());
        assertThat(problemUpdate.getValue().getTestCaseCount()).isEqualTo(2);
        verify(problemService).clearStdTested(PROBLEM_ID);
        verify(caseCacheInvalidator).evictProblemCases(PROBLEM_ID);
    }

    @Test
    void deleteOnCustomScoreSetKeepsOriginalScores() {
        List<ProblemCase> before = List.of(caseOf(1L, 1, 40), caseOf(2L, 2, 30), caseOf(3L, 3, 30));
        when(problemCaseService.getById(3L)).thenReturn(before.get(2));
        when(accessGuard.requireManagedProblem(PROBLEM_ID)).thenReturn(problem);
        when(problemCaseService.getProblemCases(PROBLEM_ID))
                .thenReturn(before)
                .thenReturn(List.of(before.get(0), before.get(1)));

        service.deleteCase(3L);

        ArgumentCaptor<ProblemCase> updated = ArgumentCaptor.forClass(ProblemCase.class);
        verify(problemCaseService, times(2)).updateById(updated.capture());
        assertThat(updated.getAllValues())
                .extracting(ProblemCase::getScore, ProblemCase::getCaseOrder)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(40, 1),
                        org.assertj.core.groups.Tuple.tuple(30, 2));
    }

    @Test
    void appendOnStandardSetResplitsAllCasesToHundred() {
        List<ProblemCase> existing = List.of(caseOf(1L, 1, 50), caseOf(2L, 2, 50));
        when(accessGuard.requireManagedProblem(PROBLEM_ID)).thenReturn(problem);
        when(problemCaseService.getProblemCases(PROBLEM_ID)).thenReturn(existing);
        when(problemCaseService.save(any(ProblemCase.class))).thenAnswer(invocation -> {
            ProblemCase saved = invocation.getArgument(0);
            saved.setId(100L + saved.getCaseOrder());
            return true;
        });
        when(testCaseStorageService.writeInput(anyLong(), anyLong(), any())).thenReturn("path/in");
        when(testCaseStorageService.writeOutput(anyLong(), anyLong(), any())).thenReturn("path/out");

        var result = service.appendCases(PROBLEM_ID, List.of(
                new FpsTestCase("3", "30"), new FpsTestCase("4", "40")));

        assertThat(result.appended()).isEqualTo(2);
        assertThat(result.skipped()).isZero();
        assertThat(result.totalCount()).isEqualTo(4);
        assertThat(result.note()).isNull();

        // 4 组用例：总分 100，每组 25
        assertThat(existing).extracting(ProblemCase::getScore).containsExactly(25, 25);
        verify(caseCacheInvalidator).evictProblemCases(PROBLEM_ID);
        verify(problemService, times(1)).updateById(any(Problem.class));
        verify(problemService).clearStdTested(PROBLEM_ID);
    }

    @Test
    void appendAllDuplicatesLeavesScoresAndTestedStateUntouched() {
        List<ProblemCase> existing = List.of(caseOf(1L, 1, 50), caseOf(2L, 2, 50));
        when(accessGuard.requireManagedProblem(PROBLEM_ID)).thenReturn(problem);
        when(problemCaseService.getProblemCases(PROBLEM_ID)).thenReturn(existing);
        when(testCaseStorageService.readByRelativePath("11/in")).thenReturn("1");
        when(testCaseStorageService.readByRelativePath("12/in")).thenReturn("2");
        existing.get(0).setInputFilePath("11/in");
        existing.get(1).setInputFilePath("12/in");

        var result = service.appendCases(PROBLEM_ID, List.of(new FpsTestCase(" 1 \r\n", "whatever")));

        assertThat(result.appended()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(existing).extracting(ProblemCase::getScore).containsExactly(50, 50);
        verify(problemCaseService, never()).save(any());
        verify(problemService, never()).updateById(any(Problem.class));
        verify(problemService, never()).clearStdTested(anyLong());
        verifyNoInteractions(caseCacheInvalidator);
    }

    @Test
    void appendOnCustomScoreSetSharesOnlyRemainingBudget() {
        List<ProblemCase> existing = List.of(caseOf(1L, 1, 40), caseOf(2L, 2, 30));
        when(accessGuard.requireManagedProblem(PROBLEM_ID)).thenReturn(problem);
        when(problemCaseService.getProblemCases(PROBLEM_ID)).thenReturn(existing);
        when(problemCaseService.save(any(ProblemCase.class))).thenAnswer(invocation -> {
            ProblemCase saved = invocation.getArgument(0);
            saved.setId(200L);
            return true;
        });
        when(testCaseStorageService.writeInput(anyLong(), anyLong(), any())).thenReturn("path/in");
        when(testCaseStorageService.writeOutput(anyLong(), anyLong(), any())).thenReturn("path/out");

        var result = service.appendCases(PROBLEM_ID, List.of(new FpsTestCase("9", "90")));

        // 既有 40 + 30 = 70，剩余 30 全部分给新用例；既有分值保持不变
        assertThat(existing).extracting(ProblemCase::getScore).containsExactly(40, 30);
        ArgumentCaptor<ProblemCase> saved = ArgumentCaptor.forClass(ProblemCase.class);
        verify(problemCaseService).save(saved.capture());
        assertThat(saved.getValue().getScore()).isEqualTo(30);
        assertThat(saved.getValue().getCaseOrder()).isEqualTo(3);
        assertThat(result.note()).contains("自定义分值");
    }

    @Test
    void appendOverLimitIsRejected() {
        List<ProblemCase> existing = standardSet(ProblemCaseApplicationService.MAX_CASES_PER_PROBLEM);
        when(accessGuard.requireManagedProblem(PROBLEM_ID)).thenReturn(problem);
        when(problemCaseService.getProblemCases(PROBLEM_ID)).thenReturn(existing);

        assertThatThrownBy(() -> service.appendCases(PROBLEM_ID, List.of(new FpsTestCase("x", "y"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上限");

        verify(problemCaseService, never()).save(any());
    }

    @Test
    void batchSaveOverLimitIsRejectedBeforeClearingExistingCases() {
        when(accessGuard.requireManagedProblem(PROBLEM_ID)).thenReturn(problem);
        List<FpsTestCase> tooMany = IntStream
                .range(0, ProblemCaseApplicationService.MAX_CASES_PER_PROBLEM + 1)
                .mapToObj(i -> new FpsTestCase("in" + i, "out" + i))
                .toList();

        assertThatThrownBy(() -> service.saveCases(PROBLEM_ID, tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上限");

        verify(problemCaseService, never()).remove(any());
    }

    @Test
    void clearingAlreadyEmptyCasesDoesNotTouchTestedStd() {
        when(accessGuard.requireManagedProblem(PROBLEM_ID)).thenReturn(problem);
        when(problemCaseService.getProblemCases(PROBLEM_ID)).thenReturn(List.of());

        service.clearCases(PROBLEM_ID);

        ArgumentCaptor<Problem> updates = ArgumentCaptor.forClass(Problem.class);
        verify(problemService).updateById(updates.capture());
        assertThat(updates.getValue().getTestCaseCount()).isZero();
        assertThat(updates.getValue().getStdTested()).isNull();
        verify(problemService, never()).clearStdTested(anyLong());
        verify(caseCacheInvalidator).evictProblemCases(PROBLEM_ID);
    }

    @Test
    void clearingCasesInvalidatesTestedStd() {
        when(accessGuard.requireManagedProblem(PROBLEM_ID)).thenReturn(problem);
        when(problemCaseService.getProblemCases(PROBLEM_ID)).thenReturn(standardSet(2));

        service.clearCases(PROBLEM_ID);

        verify(problemService).updateById(any(Problem.class));
        verify(problemService).clearStdTested(PROBLEM_ID);
    }

    @Test
    void standardScoreSetDetectionMatchesEqualSplitRule() {
        assertThat(ProblemCaseApplicationService.isStandardScoreSet(List.of())).isTrue();
        assertThat(ProblemCaseApplicationService.isStandardScoreSet(standardSet(3))).isTrue();
        // 两组固定 10 分不是等分规则（应为 50/50），视为特殊结构
        assertThat(ProblemCaseApplicationService.isStandardScoreSet(
                List.of(caseOf(1L, 1, 10), caseOf(2L, 2, 10)))).isFalse();
        // 子任务号不一致同样视为特殊结构
        ProblemCase otherSubtask = caseOf(2L, 2, 50);
        otherSubtask.setSubtaskNo(2);
        assertThat(ProblemCaseApplicationService.isStandardScoreSet(
                List.of(caseOf(1L, 1, 50), otherSubtask))).isFalse();
    }

    @Test
    void importedCaseScoreKeepsHundredTotalEvenBeyondOneHundredCases() {
        int count = 150;
        int total = IntStream.range(0, count)
                .map(index -> ProblemCaseApplicationService.importedCaseScore(index, count))
                .sum();
        assertThat(total).isEqualTo(ProblemCaseApplicationService.TARGET_TOTAL_SCORE);

        // 150 组：前 100 组 1 分，其余 0 分，不会出现负数或越界
        assertThat(ProblemCaseApplicationService.importedCaseScore(99, count)).isEqualTo(1);
        assertThat(ProblemCaseApplicationService.importedCaseScore(100, count)).isZero();
        assertThat(ProblemCaseApplicationService.importedCaseScore(149, count)).isZero();
    }

    @Test
    void caseFingerprintChangesWhenCasesChange() {
        when(problemCaseService.getProblemCases(PROBLEM_ID)).thenReturn(standardSet(2));
        String before = service.caseFingerprint(PROBLEM_ID);

        when(problemCaseService.getProblemCases(PROBLEM_ID)).thenReturn(standardSet(3));
        String after = service.caseFingerprint(PROBLEM_ID);

        assertThat(before).isNotBlank().isNotEqualTo(after);
    }

    @Test
    void saveCasesLocksProblemRowBeforeDeletingExistingCases() {
        when(accessGuard.requireManagedProblem(PROBLEM_ID)).thenReturn(problem);
        when(problemCaseService.getProblemCases(PROBLEM_ID)).thenReturn(standardSet(1));
        when(problemCaseService.save(any(ProblemCase.class))).thenAnswer(invocation -> {
            ProblemCase saved = invocation.getArgument(0);
            saved.setId(501L);
            return true;
        });
        when(testCaseStorageService.writeInput(anyLong(), anyLong(), any())).thenReturn("in");
        when(testCaseStorageService.writeOutput(anyLong(), anyLong(), any())).thenReturn("out");

        service.saveCases(PROBLEM_ID, List.of(new FpsTestCase("1", "1")));

        // 加锁必须早于「删除旧用例」：否则并发保存标准答案会与本次删除交叉
        InOrder order = inOrder(jdbcTemplate, problemCaseService);
        order.verify(jdbcTemplate).queryForList(anyString(), eq(Long.class), eq(PROBLEM_ID));
        order.verify(problemCaseService).remove(any());
    }

    @Test
    void saveCasesDeletesOldCaseFilesAfterCommitOnly() {
        ProblemCase old = caseOf(11L, 1, 100);
        when(accessGuard.requireManagedProblem(PROBLEM_ID)).thenReturn(problem);
        when(problemCaseService.getProblemCases(PROBLEM_ID)).thenReturn(List.of(old));
        when(problemCaseService.save(any(ProblemCase.class))).thenAnswer(invocation -> {
            ProblemCase saved = invocation.getArgument(0);
            saved.setId(777L);
            return true;
        });
        when(testCaseStorageService.writeInput(anyLong(), anyLong(), any())).thenReturn("in");
        when(testCaseStorageService.writeOutput(anyLong(), anyLong(), any())).thenReturn("out");

        service.saveCases(PROBLEM_ID, List.of(new FpsTestCase("1", "1")));

        // 旧文件延迟到提交后删除；新文件在事务回滚时清理
        verify(testCaseStorageService).deleteCaseDirsAfterCommit(PROBLEM_ID, List.of(11L));
        verify(testCaseStorageService).deleteCaseDirsOnRollback(eq(PROBLEM_ID), any());
        verify(testCaseStorageService, never()).deleteByProblemId(anyLong());
        verify(testCaseStorageService, never()).deleteCase(anyLong(), anyLong());
    }

    @Test
    void lockThenFingerprintUsesShareLockedReadInsteadOfSnapshotRead() {
        when(problemCaseService.getProblemCasesForShare(PROBLEM_ID)).thenReturn(standardSet(2));

        String fingerprint = service.lockProblemRowThenFingerprint(PROBLEM_ID);

        assertThat(fingerprint).isNotBlank();
        InOrder order = inOrder(jdbcTemplate, problemCaseService);
        order.verify(jdbcTemplate).queryForList(anyString(), eq(Long.class), eq(PROBLEM_ID));
        order.verify(problemCaseService).getProblemCasesForShare(PROBLEM_ID);
        verify(problemCaseService, never()).getProblemCases(anyLong());
    }
}
