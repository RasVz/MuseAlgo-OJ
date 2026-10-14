package top.everblue.oj.judge.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import top.everblue.oj.api.judge.JudgeGateway;
import top.everblue.oj.api.problem.ProblemQuery;
import top.everblue.oj.api.problem.TestCaseView;
import top.everblue.oj.judge.domain.SubmissionStatus;
import top.everblue.oj.judge.entity.Submission;
import top.everblue.oj.judge.entity.SubmissionCode;
import top.everblue.oj.judge.entity.SubmissionResult;
import top.everblue.oj.judge.mapper.SubmissionCodeMapper;
import top.everblue.oj.judge.mapper.SubmissionMapper;
import top.everblue.oj.judge.mapper.SubmissionResultMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 判题编排器（seam：JudgeOrchestrator.executeJudge）。
 * JudgeGateway 使用 stub 返回固定结果，验证：
 * AC 累计分数 / WA 快停 / CE 错误信息 / RE 信息 / 空用例报错 / 标准化结果落库。
 *
 * <p>用例并行（A3-1）后，测试注入<b>单线程执行器</b>保证用例按序执行，
 * 原有"组内跳过"断言语义与改造前一致。</p>
 */
@ExtendWith(MockitoExtension.class)
class JudgeOrchestratorTest {

    @Mock
    private JudgeGateway judgeGateway;
    @Mock
    private SubmissionMapper submissionMapper;
    @Mock
    private SubmissionCodeMapper submissionCodeMapper;
    @Mock
    private ProblemQuery problemQuery;
    @Mock
    private SubmissionResultMapper submissionResultMapper;
    @InjectMocks
    private JudgeOrchestrator orchestrator;

    private ExecutorService caseExecutor;

    @BeforeEach
    void setUpCaseExecutor() {
        caseExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "judge-test-case");
            t.setDaemon(true);
            return t;
        });
        ReflectionTestUtils.setField(orchestrator, "caseExecutor", caseExecutor);
        // V50 拆表：判题从 submission_code 补载源码（mock 返回固定代码；lenient：缺 submission 的用例不触发）
        org.mockito.Mockito.lenient().when(submissionCodeMapper.selectById(any()))
                .thenAnswer(inv -> {
                    SubmissionCode sc = new SubmissionCode();
                    sc.setSubmissionId((Long) inv.getArgument(0));
                    sc.setCode("int main(){}");
                    return sc;
                });
    }

    private Submission submission(Long id) {
        Submission s = new Submission();
        s.setId(id);
        s.setProblemId(100L);
        s.setCode("int main(){}");
        s.setLanguage("cpp");
        return s;
    }

    private TestCaseView caseOf(int order, Integer score) {
        return caseOf(order, score, null);
    }

    private TestCaseView caseOf(int order, Integer score, Integer subtaskNo) {
        return new TestCaseView((long) order, 100L, "input" + order, "output" + order,
                score, subtaskNo, order, (byte) 1);
    }

    private void stubBasics(Submission s, List<TestCaseView> cases) {
        when(submissionMapper.selectById(s.getId())).thenReturn(s);
        when(problemQuery.findProblemLimits(100L)).thenReturn(new int[]{1000, 65536});
        when(problemQuery.findTestCases(100L)).thenReturn(cases);
    }

    @Test
    void acceptedSubmissionSumsScoresAndWritesResult() {
        Submission s = submission(1L);
        stubBasics(s, List.of(caseOf(1, 30), caseOf(2, 70)));
        when(judgeGateway.judge(any(), any(), any(), any(), anyDouble(), anyInt()))
                .thenReturn(Map.of("status", 3, "time", 0.05, "memory", 1024));
        ReflectionTestUtils.setField(orchestrator, "persistNormalizedResults", true);

        assertEquals(SubmissionStatus.ACCEPTED, orchestrator.executeJudge(1L));

        ArgumentCaptor<Submission> captor = ArgumentCaptor.forClass(Submission.class);
        verify(submissionMapper, atLeast(2)).updateById(captor.capture());
        Submission last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(SubmissionStatus.ACCEPTED.code(), last.getStatus());
        assertEquals("FINISHED", last.getJudgePhase());
        assertEquals(100, last.getScore());
        assertEquals(50, last.getRunTime());
        assertEquals(1024, last.getRunMemory());
        assertNull(last.getErrorMessage());
        verify(submissionResultMapper).insert(any(SubmissionResult.class));
    }

    @Test
    void wrongAnswerFastFailsWithoutCallingRemainingCases() {
        Submission s = submission(2L);
        // 单组（subtask_no=1）：组内失败后跳过剩余同组用例
        stubBasics(s, List.of(caseOf(1, 10, 1), caseOf(2, 10, 1), caseOf(3, 10, 1)));
        when(judgeGateway.judge(any(), any(), any(), any(), anyDouble(), anyInt()))
                .thenReturn(Map.of("status", 3, "time", 0.01, "memory", 256))
                .thenReturn(Map.of("status", 4, "time", 0.02, "memory", 512));

        assertEquals(SubmissionStatus.WRONG_ANSWER, orchestrator.executeJudge(2L));

        verify(judgeGateway, org.mockito.Mockito.times(2))
                .judge(any(), any(), any(), any(), anyDouble(), anyInt());
        ArgumentCaptor<Submission> captor = ArgumentCaptor.forClass(Submission.class);
        verify(submissionMapper, atLeast(2)).updateById(captor.capture());
        Submission last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(SubmissionStatus.WRONG_ANSWER.code(), last.getStatus());
        assertEquals(0, last.getScore());
        assertNull(last.getErrorMessage());
    }

    @Test
    void partialScoreAcrossSubtasksWhenOneGroupFails() {
        Submission s = submission(6L);
        // 组1：2 用例 ×30（全 AC → 60）；组2：2 用例 ×20（第 4 用例 WA → 整组 0 分）
        stubBasics(s, List.of(
                caseOf(1, 30, 1), caseOf(2, 30, 1),
                caseOf(3, 20, 2), caseOf(4, 20, 2)));
        when(judgeGateway.judge(any(), any(), any(), any(), anyDouble(), anyInt()))
                .thenReturn(Map.of("status", 3, "time", 0.01, "memory", 128))
                .thenReturn(Map.of("status", 3, "time", 0.01, "memory", 128))
                .thenReturn(Map.of("status", 3, "time", 0.01, "memory", 128))
                .thenReturn(Map.of("status", 4, "time", 0.02, "memory", 256));

        assertEquals(SubmissionStatus.WRONG_ANSWER, orchestrator.executeJudge(6L));

        ArgumentCaptor<Submission> captor = ArgumentCaptor.forClass(Submission.class);
        verify(submissionMapper, atLeast(2)).updateById(captor.capture());
        Submission last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(60, last.getScore());
    }

    @Test
    void failedSubtaskSkipsRemainingCasesInSameGroup() {
        Submission s = submission(7L);
        // 组2 第 1 个用例失败 → 组2 第 2 个用例不再调 Judge0
        stubBasics(s, List.of(
                caseOf(1, 25, 1), caseOf(2, 25, 1),
                caseOf(3, 25, 2), caseOf(4, 25, 2)));
        when(judgeGateway.judge(any(), any(), any(), any(), anyDouble(), anyInt()))
                .thenReturn(Map.of("status", 3, "time", 0.01, "memory", 128))
                .thenReturn(Map.of("status", 3, "time", 0.01, "memory", 128))
                .thenReturn(Map.of("status", 4, "time", 0.02, "memory", 256));

        assertEquals(SubmissionStatus.WRONG_ANSWER, orchestrator.executeJudge(7L));

        verify(judgeGateway, org.mockito.Mockito.times(3))
                .judge(any(), any(), any(), any(), anyDouble(), anyInt());
        ArgumentCaptor<Submission> captor = ArgumentCaptor.forClass(Submission.class);
        verify(submissionMapper, atLeast(2)).updateById(captor.capture());
        Submission last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(50, last.getScore()); // 组1 全 AC 得 50，组2 0 分
    }

    @Test
    void compileErrorCapturesCompilerOutput() {
        Submission s = submission(3L);
        stubBasics(s, List.of(caseOf(1, 100)));
        when(judgeGateway.judge(any(), any(), any(), any(), anyDouble(), anyInt()))
                .thenReturn(Map.of("status", 6, "time", 0, "memory", 0,
                        "compile_output", "error: expected ';'"));

        assertEquals(SubmissionStatus.COMPILATION_ERROR, orchestrator.executeJudge(3L));

        ArgumentCaptor<Submission> captor = ArgumentCaptor.forClass(Submission.class);
        verify(submissionMapper, atLeast(2)).updateById(captor.capture());
        Submission last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals("error: expected ';'", last.getErrorMessage());
    }

    @Test
    void runtimeErrorFallsBackToStderr() {
        Submission s = submission(4L);
        stubBasics(s, List.of(caseOf(1, 100)));
        when(judgeGateway.judge(any(), any(), any(), any(), anyDouble(), anyInt()))
                .thenReturn(Map.of("status", 7, "time", 0, "memory", 0, "stderr", "Segmentation fault"));

        assertEquals(SubmissionStatus.RUNTIME_ERROR, orchestrator.executeJudge(4L));

        ArgumentCaptor<Submission> captor = ArgumentCaptor.forClass(Submission.class);
        verify(submissionMapper, atLeast(2)).updateById(captor.capture());
        Submission last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals("Segmentation fault", last.getErrorMessage());
    }

    @Test
    void missingSubmissionIsRejected() {
        when(submissionMapper.selectById(99L)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> orchestrator.executeJudge(99L));
    }

    @Test
    void emptyTestCasesAreRejected() {
        Submission s = submission(5L);
        when(submissionMapper.selectById(5L)).thenReturn(s);
        when(problemQuery.findTestCases(100L)).thenReturn(List.of());
        assertThrows(IllegalStateException.class, () -> orchestrator.executeJudge(5L));
        verify(judgeGateway, never()).judge(any(), any(), any(), any(), anyDouble(), anyInt());
    }
}
