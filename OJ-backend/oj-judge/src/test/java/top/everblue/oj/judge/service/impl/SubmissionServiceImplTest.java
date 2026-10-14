package top.everblue.oj.judge.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import top.everblue.oj.api.problem.ProblemQuery;
import top.everblue.oj.api.problem.TestCaseView;
import top.everblue.oj.judge.domain.SubmissionStatus;
import top.everblue.oj.judge.entity.Submission;
import top.everblue.oj.judge.mapper.SubmissionCodeMapper;
import top.everblue.oj.judge.mapper.SubmissionMapper;
import top.everblue.oj.judge.messaging.JudgeTaskNotifier;
import top.everblue.oj.judge.service.JudgeTaskService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 判题编排核销（回归契约）：submitCode 只负责「查用例 → 落库 PENDING → 入队」，
 * 判题执行唯一经 {@link top.everblue.oj.judge.worker.JudgeOrchestrator}（由 JudgeWorker 触发），
 * 提交路径绝不直接触发判题。
 *
 * <p>历史债务「JudgeWorker 与 SubmissionServiceImpl 判题循环重复 ~70 行」
 * 已在 2026-08-01 O4（JudgeOrchestrator 抽取）解决，本用例将其锁定为回归契约：
 * 若未来有人把判题循环塞回 submitCode，本测试将失败。</p>
 */
@ExtendWith(MockitoExtension.class)
class SubmissionServiceImplTest {

    @Mock
    private ProblemQuery problemQuery;
    @Mock
    private JudgeTaskService judgeTaskService;
    @Mock
    private ObjectProvider<JudgeTaskNotifier> judgeTaskNotifier;
    @Mock
    private SubmissionCodeMapper submissionCodeMapper;
    @Mock
    private SubmissionMapper submissionMapper;
    @InjectMocks
    private SubmissionServiceImpl submissionService;

    @BeforeEach
    void setUpBaseMapper() {
        // ServiceImpl.save 依赖 baseMapper，@InjectMocks 不会按父类字段名注入
        ReflectionTestUtils.setField(submissionService, "baseMapper", submissionMapper);
    }

    private Submission submission(Long id) {
        Submission s = new Submission();
        s.setId(id);
        s.setProblemId(100L);
        s.setCode("int main(){}");
        s.setLanguage("cpp");
        return s;
    }

    private TestCaseView caseOf(int order) {
        return new TestCaseView((long) order, 100L, "input" + order, "output" + order,
                null, null, order, (byte) 1);
    }

    @Test
    void submitCodeOnlyEnqueuesTaskAndNeverJudgesDirectly() {
        Submission s = submission(1L);
        when(problemQuery.findTestCases(100L)).thenReturn(List.of(caseOf(1), caseOf(2)));

        Submission result = submissionService.submitCode(s);

        // 提交后进入 PENDING / QUEUED，判题任务只经 JudgeTaskService.submit 入队
        assertEquals(SubmissionStatus.PENDING.code(), result.getStatus());
        assertEquals("QUEUED", result.getJudgePhase());

        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        verify(judgeTaskService).submit(idCaptor.capture());
        assertEquals(1L, idCaptor.getValue());

        // MQ 唤醒（best-effort，失败由 DB 轮询兜底）
        verify(judgeTaskNotifier).ifAvailable(any());

        // 回归契约：提交路径不直接领取任务、不直接判题
        verify(judgeTaskService, never()).claimBatch(anyInt());
    }

    @Test
    void submitCodeRejectsProblemWithoutTestCases() {
        Submission s = submission(2L);
        when(problemQuery.findTestCases(100L)).thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> submissionService.submitCode(s));

        verify(judgeTaskService, never()).submit(any());
        verify(judgeTaskService, never()).claimBatch(anyInt());
    }
}
