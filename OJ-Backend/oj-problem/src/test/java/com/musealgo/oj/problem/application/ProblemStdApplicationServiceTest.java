package com.musealgo.oj.problem.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.service.ProblemService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 「测试并保存」的一致性边界：用例集合在验证期间变化时绝不能落库成「已测试」。
 * <p>
 * 这里固定的是调用契约（先加锁 + 锁定读重算指纹，再比对，再写库）；
 * 真实并发交错由真实 MySQL 的 IT 验证，单测不替代数据库行为。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ProblemStdApplicationServiceTest {

    private static final Long PROBLEM_ID = 9L;

    @Mock
    private ProblemService problemService;
    @Mock
    private ProblemAccessGuard accessGuard;
    @Mock
    private ProblemCaseApplicationService caseApplicationService;

    private ProblemStdApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ProblemStdApplicationService(problemService, accessGuard, caseApplicationService);
        lenient().when(problemService.updateStd(anyLong(), any(), any(), anyInt())).thenReturn(true);
    }

    @Test
    void matchingFingerprintSavesStdAsTested() {
        when(caseApplicationService.lockProblemRowThenFingerprint(PROBLEM_ID)).thenReturn("fp-1");
        Problem problem = new Problem();
        problem.setId(PROBLEM_ID);
        when(accessGuard.requireManagedProblem(PROBLEM_ID)).thenReturn(problem);

        service.saveStdTested(PROBLEM_ID, "int main(){}", "思路", "fp-1");

        InOrder order = inOrder(caseApplicationService, problemService);
        order.verify(caseApplicationService).lockProblemRowThenFingerprint(PROBLEM_ID);
        order.verify(problemService).updateStd(PROBLEM_ID, "int main(){}", "思路", 1);
    }

    @Test
    void changedFingerprintIsRejectedWithoutTouchingProblemRow() {
        when(caseApplicationService.lockProblemRowThenFingerprint(PROBLEM_ID)).thenReturn("fp-2");

        assertThatThrownBy(() -> service.saveStdTested(PROBLEM_ID, "int main(){}", "", "fp-1"))
                .isInstanceOf(CaseSetChangedException.class)
                .hasMessageContaining("测试用例已被修改");

        verify(problemService, never()).updateStd(anyLong(), any(), any(), anyInt());
        verifyNoInteractions(accessGuard);
    }

    @Test
    void blankCodeIsRejectedBeforeTakingAnyLock() {
        assertThatThrownBy(() -> service.saveStdTested(PROBLEM_ID, "  ", "", "fp-1"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(caseApplicationService);
        verifyNoInteractions(problemService);
    }

    @Test
    void forceSaveNeverMarksTested() {
        Problem problem = new Problem();
        problem.setId(PROBLEM_ID);
        when(accessGuard.requireManagedProblem(PROBLEM_ID)).thenReturn(problem);

        service.saveStd(PROBLEM_ID, "int main(){}", "思路");

        verify(problemService).updateStd(eq(PROBLEM_ID), eq("int main(){}"), eq("思路"), eq(0));
    }

    @Test
    void clearStdResetsTestedFlag() {
        Problem problem = new Problem();
        problem.setId(PROBLEM_ID);
        when(accessGuard.requireManagedProblem(PROBLEM_ID)).thenReturn(problem);

        service.clearStd(PROBLEM_ID);

        verify(problemService).updateStd(PROBLEM_ID, null, null, 0);
    }
}
