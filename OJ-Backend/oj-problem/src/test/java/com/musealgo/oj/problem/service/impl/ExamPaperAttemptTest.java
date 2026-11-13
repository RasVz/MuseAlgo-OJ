package com.musealgo.oj.problem.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.api.submission.SubmissionAttemptQuery;
import com.musealgo.oj.problem.application.ExamAttemptApplicationService;
import com.musealgo.oj.problem.application.ExamPaperStatsApplicationService;
import com.musealgo.oj.problem.entity.ExamAnswer;
import com.musealgo.oj.problem.entity.ExamAttempt;
import com.musealgo.oj.problem.entity.ExamPaper;
import com.musealgo.oj.problem.entity.ExamPaperProblem;
import com.musealgo.oj.problem.entity.ExamQuestion;
import com.musealgo.oj.problem.entity.dto.ExamAnswerSubmitDTO;
import com.musealgo.oj.problem.entity.dto.ExamPaperDTO;
import com.musealgo.oj.problem.entity.vo.ExamAttemptVO;
import com.musealgo.oj.problem.mapper.ExamAnswerMapper;
import com.musealgo.oj.problem.mapper.ExamAttemptMapper;
import com.musealgo.oj.problem.mapper.ExamPaperMapper;
import com.musealgo.oj.problem.mapper.ExamPaperProblemMapper;
import com.musealgo.oj.problem.mapper.ExamQuestionMapper;
import com.musealgo.oj.problem.mapper.ProblemMapper;

import java.time.LocalDateTime;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

/**
 * 限时作答会话（seam：ExamPaperService.startAttempt / finishAttempt）。
 */
@ExtendWith(MockitoExtension.class)
class ExamPaperAttemptTest {

    @Mock
    private ExamPaperMapper examPaperMapper;
    @Mock
    private ExamPaperProblemMapper examPaperProblemMapper;
    @Mock
    private ExamQuestionMapper examQuestionMapper;
    @Mock
    private ExamAnswerMapper examAnswerMapper;
    @Mock
    private ExamAttemptMapper examAttemptMapper;
    @Mock
    private SubmissionAttemptQuery submissionAttemptQuery;
    @Mock
    private ProblemMapper problemMapper;
    @Mock
    private ExamAttemptApplicationService attemptApplicationService;
    @Mock
    private ExamPaperStatsApplicationService statsApplicationService;

    @InjectMocks
    private ExamPaperServiceImpl service;

    /** 作答会话域（拆分后实测目标：事务与判分逻辑所在） */
    @InjectMocks
    private ExamAttemptApplicationService attemptFlow;

    @BeforeEach
    void stubPublishedPaperForAttemptRequests() {
        lenient().when(examPaperMapper.selectById(10L)).thenReturn(paper(90));
    }

    private ExamPaper paper(int duration) {
        ExamPaper paper = new ExamPaper();
        paper.setId(10L);
        paper.setDurationMinutes(duration);
        paper.setTotalScore(100);
        paper.setIsPublished(1);
        return paper;
    }

    @Test
    void startAttemptUsesPaperDurationAsDeadline() {
        when(examPaperMapper.selectByIdForUpdate(10L)).thenReturn(paper(90));
        when(examAttemptMapper.selectRunningByPaperUserForUpdate(anyLong(), anyLong())).thenReturn(null);
        doAnswer(inv -> {
            ((ExamAttempt) inv.getArgument(0)).setId(1L); // 模拟 MyBatis-Plus 回填自增 ID
            return 1;
        }).when(examAttemptMapper).insert(any(ExamAttempt.class));

        ExamAttemptVO vo = attemptFlow.startAttempt(10L, 1L);

        assertNotNull(vo.getId());
        assertEquals(ExamAttempt.STATUS_RUNNING, vo.getStatus());
        assertEquals(90L * 60, vo.getRemainingSeconds(), 60);
        assertTrue(vo.getEndTime().isAfter(vo.getStartTime()));
        verify(examPaperMapper).selectByIdForUpdate(10L);
    }

    @Test
    void startAttemptKeepsConcurrencyLockInsideTransaction() throws Exception {
        Method method = ExamAttemptApplicationService.class.getMethod(
                "startAttempt", Long.class, Long.class);

        assertNotNull(method.getAnnotation(Transactional.class));
    }

    @Test
    void finishAttemptMergesObjectiveAndCodingScores() {
        ExamAttempt running = new ExamAttempt();
        running.setId(1L);
        running.setPaperId(10L);
        running.setUserId(1L);
        running.setStatus(ExamAttempt.STATUS_RUNNING);
        running.setStartTime(LocalDateTime.now().minusMinutes(10));
        running.setEndTime(LocalDateTime.now().plusMinutes(80));
        when(examAttemptMapper.selectRunningByPaperUserForUpdate(anyLong(), anyLong())).thenReturn(running);

        ExamAnswer a1 = new ExamAnswer();
        a1.setScore(5);
        ExamAnswer a2 = new ExamAnswer();
        a2.setScore(10);
        when(examAnswerMapper.selectList(any())).thenReturn(List.of(a1, a2));

        ExamPaperProblem p1 = new ExamPaperProblem();
        p1.setProblemId(100L);
        p1.setScore(100);
        ExamPaperProblem p2 = new ExamPaperProblem();
        p2.setProblemId(200L);
        p2.setScore(100);
        when(examPaperProblemMapper.selectList(any())).thenReturn(List.of(p1, p2));
        when(submissionAttemptQuery.findBestScores(anyLong(), any(), any(), any()))
                .thenReturn(List.of(
                        new SubmissionAttemptQuery.SubmissionAttemptScore(100L, 100),
                        new SubmissionAttemptQuery.SubmissionAttemptScore(200L, 50)));

        ExamAttemptVO vo = attemptFlow.finishAttempt(10L, 1L);

        assertEquals(ExamAttempt.STATUS_SUBMITTED, vo.getStatus());
        assertEquals(15, vo.getObjectiveScore());
        assertEquals(150, vo.getCodingScore());
        assertEquals(165, vo.getTotalScore());
        verify(examAttemptMapper).updateById(any(ExamAttempt.class));
    }

    @Test
    void codingScoreUsesPaperPointsAndManualFinishDeadline() {
        ExamAttempt running = runningAttempt();
        running.setEndTime(LocalDateTime.now().plusMinutes(20));
        when(examAttemptMapper.selectRunningByPaperUserForUpdate(anyLong(), anyLong())).thenReturn(running);
        when(examAnswerMapper.selectList(any())).thenReturn(List.of());

        ExamPaperProblem p1 = new ExamPaperProblem();
        p1.setProblemId(100L);
        p1.setScore(20);
        ExamPaperProblem p2 = new ExamPaperProblem();
        p2.setProblemId(200L);
        p2.setScore(10);
        when(examPaperProblemMapper.selectList(any())).thenReturn(List.of(p1, p2));
        when(submissionAttemptQuery.findBestScores(anyLong(), any(), any(), any()))
                .thenReturn(List.of(
                        new SubmissionAttemptQuery.SubmissionAttemptScore(100L, 50),
                        new SubmissionAttemptQuery.SubmissionAttemptScore(200L, 100)));

        ExamAttemptVO result = attemptFlow.finishAttempt(10L, 1L);

        assertEquals(20, result.getCodingScore());
        assertEquals(20, result.getTotalScore());
        org.mockito.ArgumentCaptor<LocalDateTime> deadline = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(submissionAttemptQuery).findBestScores(anyLong(), any(), any(), deadline.capture());
        assertTrue(deadline.getValue().isBefore(running.getEndTime()));
    }

    @Test
    void zeroConfiguredCodingPointsRemainZero() {
        when(examAttemptMapper.selectRunningByPaperUserForUpdate(anyLong(), anyLong())).thenReturn(runningAttempt());
        when(examAnswerMapper.selectList(any())).thenReturn(List.of());
        ExamPaperProblem problem = new ExamPaperProblem();
        problem.setProblemId(100L);
        problem.setScore(0);
        when(examPaperProblemMapper.selectList(any())).thenReturn(List.of(problem));
        when(submissionAttemptQuery.findBestScores(anyLong(), any(), any(), any()))
                .thenReturn(List.of(new SubmissionAttemptQuery.SubmissionAttemptScore(100L, 100)));

        ExamAttemptVO result = attemptFlow.finishAttempt(10L, 1L);

        assertEquals(0, result.getCodingScore());
    }

    @Test
    void autoFinalizeExpiredMarksTimedOut() {
        ExamAttempt expired = new ExamAttempt();
        expired.setId(2L);
        expired.setPaperId(10L);
        expired.setUserId(1L);
        expired.setStatus(ExamAttempt.STATUS_RUNNING);
        expired.setStartTime(LocalDateTime.now().minusHours(2));
        expired.setEndTime(LocalDateTime.now().minusMinutes(10));
        when(examAttemptMapper.selectList(any())).thenReturn(List.of(expired));
        when(examAttemptMapper.selectByIdForUpdate(2L)).thenReturn(expired);
        when(examAnswerMapper.selectList(any())).thenReturn(List.of());
        when(examPaperProblemMapper.selectList(any())).thenReturn(List.of());

        int count = attemptFlow.autoFinalizeExpired();

        assertEquals(1, count);
        assertEquals(ExamAttempt.STATUS_TIMEOUT, expired.getStatus());
        assertEquals(0, expired.getTotalScore());
    }

    @Test
    void finishingExpiredAttemptMarksTimedOut() {
        ExamAttempt expired = runningAttempt();
        expired.setEndTime(LocalDateTime.now().minusSeconds(1));
        when(examAttemptMapper.selectRunningByPaperUserForUpdate(anyLong(), anyLong())).thenReturn(expired);
        when(examAnswerMapper.selectList(any())).thenReturn(List.of());
        when(examPaperProblemMapper.selectList(any())).thenReturn(List.of());

        ExamAttemptVO result = attemptFlow.finishAttempt(10L, 1L);

        assertEquals(ExamAttempt.STATUS_TIMEOUT, result.getStatus());
    }

    @Test
    void objectiveAnswersRequireRunningAttempt() {
        when(examAttemptMapper.selectRunningByPaperUserForUpdate(anyLong(), anyLong())).thenReturn(null);
        ExamAnswerSubmitDTO submit = answerSubmit(1L, "A");

        assertThrows(com.musealgo.oj.common.exception.BusinessException.class, () -> attemptFlow.submitAnswers(10L, 1L, submit));
    }

    @Test
    void objectiveAnswersBelongToCurrentAttempt() {
        ExamAttempt running = runningAttempt();
        when(examAttemptMapper.selectRunningByPaperUserForUpdate(anyLong(), anyLong())).thenReturn(running);
        ExamQuestion question = new ExamQuestion();
        question.setId(1L);
        question.setPaperId(10L);
        question.setQuestionType("SINGLE");
        question.setAnswer("A");
        question.setScore(5);
        when(examQuestionMapper.selectList(any())).thenReturn(List.of(question));
        when(examAnswerMapper.selectList(any())).thenReturn(List.of());

        attemptFlow.submitAnswers(10L, 1L, answerSubmit(1L, "A"));

        org.mockito.ArgumentCaptor<ExamAnswer> captor = org.mockito.ArgumentCaptor.forClass(ExamAnswer.class);
        verify(examAnswerMapper).insert(captor.capture());
        assertEquals(7L, captor.getValue().getAttemptId());
        verify(examAnswerMapper, times(1)).selectList(any());
    }

    @Test
    void getAttemptRestoresSavedAnswersAndTheirImmediateResults() {
        ExamAttempt running = runningAttempt();
        when(examAttemptMapper.selectRunningByPaperUserForUpdate(anyLong(), anyLong())).thenReturn(running);
        ExamAnswer saved = new ExamAnswer();
        saved.setQuestionId(8L);
        saved.setAnswer("A");
        saved.setIsCorrect(1);
        saved.setScore(5);
        when(examAnswerMapper.selectList(any())).thenReturn(List.of(saved));
        ExamQuestion question = new ExamQuestion();
        question.setId(8L);
        question.setAnswer("A");
        question.setExplanation("说明");
        when(examQuestionMapper.selectBatchIds(any())).thenReturn(List.of(question));

        ExamAttemptVO result = attemptFlow.getAttempt(10L, 1L);

        assertEquals(1, result.getAnswers().size());
        assertEquals("A", result.getAnswers().get(0).getAnswer());
        assertEquals(1, result.getResults().size());
        assertEquals(true, result.getResults().get(0).getCorrect());
        assertEquals("说明", result.getResults().get(0).getExplanation());
    }

    @Test
    void expiredAttemptIsFinalizedAndRejectsLateAnswers() {
        ExamAttempt expired = runningAttempt();
        expired.setEndTime(LocalDateTime.now().minusSeconds(1));
        when(examAttemptMapper.selectRunningByPaperUserForUpdate(anyLong(), anyLong())).thenReturn(expired);
        when(examAnswerMapper.selectList(any())).thenReturn(List.of());
        when(examPaperProblemMapper.selectList(any())).thenReturn(List.of());

        assertThrows(com.musealgo.oj.problem.service.impl.AttemptExpiredException.class,
                () -> attemptFlow.submitAnswers(10L, 1L, answerSubmit(1L, "A")));
        assertEquals(ExamAttempt.STATUS_TIMEOUT, expired.getStatus());
    }

    @Test
    void expiredAttemptExceptionDoesNotRollbackAutoFinalize() throws Exception {
        Method method = ExamAttemptApplicationService.class.getMethod(
                "submitAnswers", Long.class, Long.class, ExamAnswerSubmitDTO.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertTrue(List.of(transactional.noRollbackFor()).contains(AttemptExpiredException.class));
    }

    @Test
    void finalizeEntryPointsAreTransactional() throws Exception {
        Method finish = ExamAttemptApplicationService.class.getMethod("finishAttempt", Long.class, Long.class);
        Method get = ExamAttemptApplicationService.class.getMethod("getAttempt", Long.class, Long.class);
        Method auto = ExamAttemptApplicationService.class.getMethod("autoFinalizeExpired");

        assertNotNull(finish.getAnnotation(Transactional.class));
        assertNotNull(get.getAnnotation(Transactional.class));
        assertNotNull(auto.getAnnotation(Transactional.class));
    }

    @Test
    void updatingPaperWithNoAttemptsRebuildsStructure() {
        ExamPaper existing = paper(90);
        when(examPaperMapper.selectByIdForUpdate(10L)).thenReturn(existing);
        ExamPaperDTO dto = new ExamPaperDTO();
        dto.setId(10L);
        dto.setTitle("更新后的试卷");
        dto.setCategory("MOCK");
        dto.setDurationMinutes(90);
        dto.setTotalScore(100);
        dto.setProblems(List.of());
        dto.setQuestions(List.of());

        service.savePaper(dto, 1L);

        verify(examAttemptMapper).delete(any());
    }

    @Test
    void updatingMetadataPreservesPaperWithAttemptHistory() {
        when(examPaperMapper.selectByIdForUpdate(10L)).thenReturn(paper(90));
        when(examAttemptMapper.selectCount(any())).thenReturn(1L);
        when(examPaperProblemMapper.selectList(any())).thenReturn(List.of());
        when(examQuestionMapper.selectList(any())).thenReturn(List.of());
        ExamPaperDTO dto = new ExamPaperDTO();
        dto.setId(10L);
        dto.setTitle("改过标题");
        dto.setDescription("新的说明");
        dto.setCategory("MOCK");
        dto.setDurationMinutes(90);
        dto.setTotalScore(100);
        dto.setProblems(List.of());
        dto.setQuestions(List.of());

        service.savePaper(dto, 1L);

        verify(examPaperMapper).updateById(any(ExamPaper.class));
        verify(examAttemptMapper, never()).delete(any());
        verify(examAnswerMapper, never()).delete(any());
        verify(examQuestionMapper, never()).delete(any());
        verify(examPaperProblemMapper, never()).delete(any());
    }

    @Test
    void structuralPaperEditIsRejectedAfterAnyAttemptExists() {
        when(examPaperMapper.selectByIdForUpdate(10L)).thenReturn(paper(90));
        when(examAttemptMapper.selectCount(any())).thenReturn(1L);
        ExamPaperDTO dto = new ExamPaperDTO();
        dto.setId(10L);
        dto.setTitle("标题");
        dto.setCategory("MOCK");
        dto.setDurationMinutes(60);
        dto.setTotalScore(100);
        dto.setProblems(List.of());
        dto.setQuestions(List.of());

        var error = assertThrows(com.musealgo.oj.common.exception.BusinessException.class,
                () -> service.savePaper(dto, 1L));

        assertEquals("EXAM_PAPER_HAS_ATTEMPTS", error.getErrorCode().key());
        verify(examPaperMapper, never()).updateById(any(ExamPaper.class));
    }

    @Test
    void deletingPaperWithAttemptHistoryIsRejected() {
        when(examPaperMapper.selectByIdForUpdate(10L)).thenReturn(paper(90));
        when(examAttemptMapper.selectCount(any())).thenReturn(1L);

        var error = assertThrows(com.musealgo.oj.common.exception.BusinessException.class,
                () -> service.deletePaper(10L));

        assertEquals("EXAM_PAPER_HAS_ATTEMPTS", error.getErrorCode().key());
        verify(examPaperMapper, never()).deleteById(any());
    }

    @Test
    void invalidObjectiveAnswerKeysAreRejectedBeforeSavingPaper() {
        ExamPaperDTO dto = new ExamPaperDTO();
        dto.setTitle("新试卷");
        dto.setCategory("MOCK");
        dto.setDurationMinutes(60);
        dto.setTotalScore(100);
        dto.setProblems(List.of());
        ExamPaperDTO.ExamQuestionItem question = new ExamPaperDTO.ExamQuestionItem();
        question.setQuestionType("MULTIPLE");
        question.setStatement("选出答案");
        question.setAnswer("AA");
        question.setScore(10);
        ExamPaperDTO.ExamOptionItem option = new ExamPaperDTO.ExamOptionItem();
        option.setKey("A");
        option.setText("选项 A");
        question.setOptions(List.of(option));
        dto.setQuestions(List.of(question));

        var error = assertThrows(com.musealgo.oj.common.exception.BusinessException.class,
                () -> service.savePaper(dto, 1L));

        assertEquals("PROBLEM_INPUT_INVALID", error.getErrorCode().key());
        verify(examPaperMapper, never()).insert(any(ExamPaper.class));
    }

    private ExamAttempt runningAttempt() {
        ExamAttempt attempt = new ExamAttempt();
        attempt.setId(7L);
        attempt.setPaperId(10L);
        attempt.setUserId(1L);
        attempt.setStatus(ExamAttempt.STATUS_RUNNING);
        attempt.setStartTime(LocalDateTime.now().minusMinutes(1));
        attempt.setEndTime(LocalDateTime.now().plusMinutes(10));
        return attempt;
    }

    private ExamAnswerSubmitDTO answerSubmit(Long questionId, String value) {
        ExamAnswerSubmitDTO.AnswerItem item = new ExamAnswerSubmitDTO.AnswerItem();
        item.setQuestionId(questionId);
        item.setAnswer(value);
        ExamAnswerSubmitDTO submit = new ExamAnswerSubmitDTO();
        submit.setAnswers(List.of(item));
        return submit;
    }

    @Test
    void scoreBucketsCoverBoundaries() {
        assertEquals("0", ExamPaperStatsApplicationService.bucketOf(0));
        assertEquals("1-59", ExamPaperStatsApplicationService.bucketOf(1));
        assertEquals("1-59", ExamPaperStatsApplicationService.bucketOf(59));
        assertEquals("60-69", ExamPaperStatsApplicationService.bucketOf(60));
        assertEquals("70-79", ExamPaperStatsApplicationService.bucketOf(79));
        assertEquals("80-89", ExamPaperStatsApplicationService.bucketOf(89));
        assertEquals("90-100", ExamPaperStatsApplicationService.bucketOf(90));
        assertEquals("90-100", ExamPaperStatsApplicationService.bucketOf(100));
    }

    @Test
    void csvEscapesCommasQuotesAndNewlines() {
        assertEquals("hello", ExamPaperStatsApplicationService.escapeCsv("hello"));
        assertEquals("\"a,b\"", ExamPaperStatsApplicationService.escapeCsv("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", ExamPaperStatsApplicationService.escapeCsv("say \"hi\""));
        assertEquals("", ExamPaperStatsApplicationService.escapeCsv(null));
    }
    @Test
    void timedOutAnswerRejectionCommitsThroughSpringTransactionBoundary() {
        ExamAttempt expired = runningAttempt();
        expired.setEndTime(LocalDateTime.now().minusSeconds(1));
        when(examAttemptMapper.selectRunningByPaperUserForUpdate(anyLong(), anyLong())).thenReturn(expired);
        when(examAnswerMapper.selectList(any())).thenReturn(List.of());
        when(examPaperProblemMapper.selectList(any())).thenReturn(List.of());
        var transactionManager = org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        var transaction = new org.springframework.transaction.support.SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(transaction);
        var advice = new org.springframework.transaction.interceptor.TransactionInterceptor();
        advice.setTransactionManager(transactionManager);
        advice.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var proxyFactory = new org.springframework.aop.framework.ProxyFactory(attemptFlow);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(advice);
        var transactional = (ExamAttemptApplicationService) proxyFactory.getProxy();

        var rejected = assertThrows(AttemptExpiredException.class,
                () -> transactional.submitAnswers(10L, 1L, answerSubmit(1L, "A")));

        assertEquals("EXAM_ATTEMPT_EXPIRED", rejected.getErrorCode().key());
        assertEquals(true, rejected.getDetails().get("autoSubmitted"));
        assertEquals(ExamAttempt.STATUS_TIMEOUT, expired.getStatus());
        verify(transactionManager).commit(transaction);
        verify(transactionManager, org.mockito.Mockito.never()).rollback(transaction);
    }
}
