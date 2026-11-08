package com.musealgo.oj.judge.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import com.musealgo.oj.api.contest.ContestSchedule;
import com.musealgo.oj.api.contest.ContestScheduleQuery;
import com.musealgo.oj.api.problem.ProblemQuery;
import com.musealgo.oj.api.problem.TestCaseView;
import com.musealgo.oj.judge.dto.RejudgeBatchScope;
import com.musealgo.oj.judge.dto.RejudgeOperationVO;
import com.musealgo.oj.judge.dto.RejudgePreviewVO;
import com.musealgo.oj.judge.entity.JudgeTask;
import com.musealgo.oj.judge.entity.RejudgeItem;
import com.musealgo.oj.judge.entity.RejudgeOperation;
import com.musealgo.oj.judge.entity.Submission;
import com.musealgo.oj.judge.mapper.JudgeTaskMapper;
import com.musealgo.oj.judge.mapper.RejudgeItemMapper;
import com.musealgo.oj.judge.mapper.RejudgeOperationMapper;
import com.musealgo.oj.judge.mapper.SubmissionMapper;
import com.musealgo.oj.judge.messaging.JudgeTaskNotifier;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * T8 单提交重判应用服务单测：A 冻结准入、VP 拒绝、幂等、重置与用例摘要。
 * （真实判题闭环在 RejudgeExecutionIT / api_smoke 验证。）
 */
@ExtendWith(MockitoExtension.class)
class RejudgeApplicationServiceTest {

    @Mock private SubmissionMapper submissionMapper;
    @Mock private JudgeTaskMapper judgeTaskMapper;
    @Mock private RejudgeOperationMapper operationMapper;
    @Mock private RejudgeItemMapper itemMapper;
    @Mock private ContestScheduleQuery contestScheduleQuery;
    @Mock private ProblemQuery problemQuery;
    @Mock private ObjectProvider<JudgeTaskNotifier> notifierProvider;
    @InjectMocks private RejudgeApplicationService service;

    @org.junit.jupiter.api.BeforeAll static void metadata() {
        for (var type : List.of(Submission.class, JudgeTask.class, RejudgeOperation.class, RejudgeItem.class)) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), "rejudge"), type);
        }
    }

    @org.junit.jupiter.api.BeforeEach void successfulWrites() {
        lenient().when(operationMapper.insert(any(RejudgeOperation.class))).thenReturn(1);
        lenient().when(itemMapper.insert(any(RejudgeItem.class))).thenReturn(1);
        lenient().when(judgeTaskMapper.insert(any(JudgeTask.class))).thenReturn(1);
    }

    private Submission submission() {
        Submission s = new Submission();
        s.setId(10L);
        s.setProblemId(1L);
        s.setUserId(7L);
        s.setStatus((byte) 1);
        s.setScore(100);
        s.setJudgePhase("FINISHED");
        s.setCreateTime(LocalDateTime.now().minusDays(1));
        return s;
    }

    private List<TestCaseView> cases() {
        return List.of(new TestCaseView(1L, 1L, "in", "out", 100, 1, 1, (byte) 1));
    }

    @Test
    void finalizedContestIsRejectedByFreezePolicy() {
        Submission s = submission();
        s.setContestId(3L);
        when(submissionMapper.selectOne(any())).thenReturn(s);
        when(contestScheduleQuery.findById(3L)).thenReturn(Optional.of(
                new ContestSchedule(3L, LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(1), "ACM", 1L)));
        when(contestScheduleQuery.isRankFinalized(3L)).thenReturn(true);

        com.musealgo.oj.common.exception.BusinessException ex = assertThrows(com.musealgo.oj.common.exception.BusinessException.class,
                () -> service.rejudgeSingleSubmission(10L, "纠正错误判题", 1L, "admin"));
        assertTrue(ex.getMessage().contains("冻结"));
        verify(operationMapper, never()).insert(any(RejudgeOperation.class));
    }

    @Test
    void runningContestIsRejected() {
        Submission s = submission();
        s.setContestId(3L);
        when(submissionMapper.selectOne(any())).thenReturn(s);
        when(contestScheduleQuery.findById(3L)).thenReturn(Optional.of(
                new ContestSchedule(3L, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1), "ACM", 1L)));
        when(contestScheduleQuery.isRankFinalized(3L)).thenReturn(false);

        assertThrows(com.musealgo.oj.common.exception.BusinessException.class,
                () -> service.rejudgeSingleSubmission(10L, "赛中修正", 1L, "admin"));
        verify(operationMapper, never()).insert(any(RejudgeOperation.class));
    }

    @Test
    void vpSubmissionIsRejectedUntilT82() {
        Submission s = submission();
        s.setVpAttemptId(99L);
        when(submissionMapper.selectOne(any())).thenReturn(s);

        assertThrows(com.musealgo.oj.common.exception.BusinessException.class,
                () -> service.rejudgeSingleSubmission(10L, "VP 修正", 1L, "admin"));
        verify(operationMapper, never()).insert(any(RejudgeOperation.class));
    }

    @Test
    void duplicatedOpenRejudgeReturnsExistingOperationWithoutResettingAgain() {
        RejudgeItem open = new RejudgeItem();
        open.setId(5L);
        open.setOperationId(9L);
        when(itemMapper.selectOpenBySubmissionId(10L)).thenReturn(open);
        when(operationMapper.selectById(9L)).thenReturn(operation(9L, 1));
        when(itemMapper.selectList(any())).thenReturn(List.of(open));

        RejudgeOperationVO vo = service.rejudgeSingleSubmission(10L, "重复点击", 1L, "admin");

        assertEquals(9L, vo.id());
        verify(submissionMapper, never()).selectOne(any());
        verify(operationMapper, never()).insert(any(RejudgeOperation.class));
    }

    @Test
    void practiceSubmissionIsResetAndQueuedWithCaseDigest() {
        Submission s = submission();
        when(itemMapper.selectOpenBySubmissionId(10L)).thenReturn(null);
        when(submissionMapper.selectOne(any())).thenReturn(s);
        when(problemQuery.findTestCases(1L)).thenReturn(cases());
        doAnswer(inv -> {
            RejudgeOperation op = inv.getArgument(0);
            op.setId(9L);
            return 1;
        }).when(operationMapper).insert(any(RejudgeOperation.class));
        doAnswer(inv -> {
            RejudgeItem item = inv.getArgument(0);
            item.setId(5L);
            return 1;
        }).when(itemMapper).insert(any(RejudgeItem.class));
        when(submissionMapper.update(isNull(), any())).thenReturn(1);
        when(operationMapper.selectById(9L)).thenAnswer(inv -> operation(9L, 1));
        when(itemMapper.selectList(any())).thenReturn(List.of());

        RejudgeOperationVO vo = service.rejudgeSingleSubmission(10L, "重新判题", 2L, "管理员");

        assertEquals("RUNNING", vo.status());
        assertEquals(1, vo.totalItems());
        ArgumentCaptor<RejudgeItem> itemCaptor = ArgumentCaptor.forClass(RejudgeItem.class);
        verify(itemMapper).insert(itemCaptor.capture());
        assertEquals(64, itemCaptor.getValue().getTestcaseDigest().length());
        assertNotNull(itemCaptor.getValue().getOldResultJson());
        verify(submissionMapper, atLeastOnce()).update(isNull(), any());
        ArgumentCaptor<JudgeTask> taskCaptor = ArgumentCaptor.forClass(JudgeTask.class);
        verify(judgeTaskMapper).insert(taskCaptor.capture());
        assertEquals(JudgeTask.STATUS_PENDING, taskCaptor.getValue().getStatus());
        assertEquals(0, taskCaptor.getValue().getAttemptCount());
        verify(notifierProvider).ifAvailable(any());
    }

    @Test
    void blankReasonIsRejectedBeforeAnyDatabaseWork() {
        assertThrows(com.musealgo.oj.common.exception.BusinessException.class,
                () -> service.rejudgeSingleSubmission(10L, "  ", 1L, "admin"));
        verifyNoInteractions(submissionMapper, operationMapper, itemMapper);
    }

    @Test
    void digestIsStableForSameCasesAndChangesWithContent() {
        String a = RejudgeApplicationService.testCaseDigest(cases());
        String b = RejudgeApplicationService.testCaseDigest(cases());
        assertEquals(a, b);
        assertEquals(64, a.length());
        String c = RejudgeApplicationService.testCaseDigest(List.of(
                new TestCaseView(1L, 1L, "in", "DIFFERENT", 100, 1, 1, (byte) 1)));
        assertNotEquals(a, c);
    }


    // ------------------------------------------------------------------
    // T8-2a 批量
    // ------------------------------------------------------------------

    private Submission candidate(Long id, Byte status, String phase, Long contestId, Long vpId) {
        Submission s = submission();
        s.setId(id);
        s.setStatus(status);
        s.setJudgePhase(phase);
        s.setContestId(contestId);
        s.setVpAttemptId(vpId);
        return s;
    }

    @Test
    void previewClassifiesEligibleBlockedStates() {
        List<Submission> candidates = List.of(
                candidate(1L, (byte) 1, "FINISHED", null, null),
                candidate(2L, (byte) 0, "QUEUED", null, null),
                candidate(3L, (byte) 1, "FINISHED", null, 77L),
                candidate(4L, (byte) 1, "FINISHED", 3L, null),
                candidate(5L, (byte) 1, "FINISHED", 4L, null));
        when(submissionMapper.selectList(any())).thenReturn(candidates);
        when(itemMapper.selectOpenSubmissionIds(any())).thenReturn(List.of());
        when(problemQuery.findTestCases(1L)).thenReturn(cases());
        when(contestScheduleQuery.findById(3L)).thenReturn(Optional.of(
                new ContestSchedule(3L, LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(1), "ACM", 1L)));
        when(contestScheduleQuery.isRankFinalized(3L)).thenReturn(true);
        when(contestScheduleQuery.findById(4L)).thenReturn(Optional.of(
                new ContestSchedule(4L, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1), "ACM", 1L)));
        when(contestScheduleQuery.isRankFinalized(4L)).thenReturn(false);

        RejudgePreviewVO preview = service.previewBatch(
                new RejudgeBatchScope("PROBLEM", 1L, null, null, null));

        assertEquals("PROBLEM", preview.scopeType());
        assertEquals(5, preview.matched());
        assertEquals(1, preview.eligible());
        assertEquals(1, preview.blockedPending());
        assertEquals(1, preview.blockedVp());
        assertEquals(1, preview.blockedFinalized());
        assertEquals(1, preview.blockedRunning());
        assertEquals(List.of(1L), preview.sampleSubmissionIds());
        assertFalse(preview.overLimit());
    }

    @Test
    void previewExcludesNoTestCasesAndInFlight() {
        List<Submission> candidates = List.of(
                candidate(1L, (byte) 1, "FINISHED", null, null),
                candidate(2L, (byte) 1, "FINISHED", null, null));
        when(submissionMapper.selectList(any())).thenReturn(candidates);
        when(itemMapper.selectOpenSubmissionIds(any())).thenReturn(List.of(2L));
        when(problemQuery.findTestCases(1L)).thenReturn(List.of());

        RejudgePreviewVO preview = service.previewBatch(
                new RejudgeBatchScope("FAILED", 1L, null, null, null));

        assertEquals(2, preview.matched());
        assertEquals(0, preview.eligible());
        assertEquals(1, preview.blockedInFlight());
        assertEquals(1, preview.blockedNoTestCases());
    }

    @Test
    void batchExecuteRejectsOverLimitBeforeWriting() {
        List<Submission> many = new ArrayList<>();
        for (long i = 1; i <= RejudgeApplicationService.MAX_BATCH_ITEMS + 1; i++) {
            many.add(candidate(i, (byte) 1, "FINISHED", null, null));
        }
        when(submissionMapper.selectList(any())).thenReturn(many);
        when(itemMapper.selectOpenSubmissionIds(any())).thenReturn(List.of());
        when(problemQuery.findTestCases(1L)).thenReturn(cases());

        RejudgePreviewVO preview = service.previewBatch(
                new RejudgeBatchScope("PROBLEM", 1L, null, null, null));
        assertTrue(preview.overLimit());

        com.musealgo.oj.common.exception.BusinessException ex = assertThrows(com.musealgo.oj.common.exception.BusinessException.class, () -> service.rejudgeBatch(
                new RejudgeBatchScope("PROBLEM", 1L, null, null, null), "批量", 1L, "admin"));
        assertTrue(ex.getMessage().contains("上限"));
        verify(operationMapper, never()).insert(any(RejudgeOperation.class));
        verify(itemMapper, never()).insert(any(RejudgeItem.class));
    }

    @Test
    void batchExecuteCreatesOperationItemsAndResetsEachSubmission() {
        List<Submission> candidates = List.of(
                candidate(1L, (byte) 1, "FINISHED", null, null),
                candidate(2L, (byte) 2, "FINISHED", null, null));
        when(submissionMapper.selectList(any())).thenReturn(candidates);
        when(itemMapper.selectOpenSubmissionIds(any())).thenReturn(List.of());
        when(problemQuery.findTestCases(1L)).thenReturn(cases());
        when(submissionMapper.update(isNull(), any())).thenReturn(1);
        doAnswer(inv -> {
            RejudgeOperation op = inv.getArgument(0);
            op.setId(20L);
            return 1;
        }).when(operationMapper).insert(any(RejudgeOperation.class));
        when(operationMapper.selectById(20L)).thenAnswer(inv -> operation(20L, 2));
        when(itemMapper.selectList(any())).thenReturn(List.of());

        RejudgeOperationVO vo = service.rejudgeBatch(
                new RejudgeBatchScope("PROBLEM", 1L, null, null, null), "批量重判", 3L, "管理员");

        assertEquals(20L, vo.id());
        assertEquals(2, vo.totalItems());
        verify(itemMapper, times(2)).insert(any(RejudgeItem.class));
        verify(submissionMapper, times(2)).update(isNull(), any());
        verify(judgeTaskMapper, times(2)).insert(any(JudgeTask.class));
    }

    private RejudgeOperation operation(Long id, int total) {
        RejudgeOperation op = new RejudgeOperation();
        op.setId(id);
        op.setOperatorId(1L);
        op.setReason("r");
        op.setScopeType(RejudgeOperation.SCOPE_SINGLE_SUBMISSION);
        op.setStatus(RejudgeOperation.STATUS_RUNNING);
        op.setTotalItems(total);
        op.setSucceededItems(0);
        op.setFailedItems(0);
        return op;
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"operation","item","task"})
    void requiredInsertFailureAbortsRejudgeAcceptance(String failure) {
        when(submissionMapper.selectOne(any())).thenReturn(submission());
        when(problemQuery.findTestCases(1L)).thenReturn(cases());
        lenient().when(submissionMapper.update(isNull(), any())).thenReturn(1);
        lenient().when(operationMapper.insert(any(RejudgeOperation.class))).thenAnswer(call -> {
            call.getArgument(0,RejudgeOperation.class).setId(9L); return failure.equals("operation") ? 0 : 1;
        });
        lenient().when(itemMapper.insert(any(RejudgeItem.class))).thenReturn(failure.equals("item") ? 0 : 1);
        lenient().when(judgeTaskMapper.insert(any(JudgeTask.class))).thenReturn(failure.equals("task") ? 0 : 1);
        lenient().when(operationMapper.selectById(9L)).thenReturn(operation(9L,1));
        lenient().when(itemMapper.selectList(any())).thenReturn(List.of());
        assertThrows(IllegalStateException.class,()->service.rejudgeSingleSubmission(10L,"synthetic",2L,"admin"));
        verify(notifierProvider,never()).ifAvailable(any());
    }

}
