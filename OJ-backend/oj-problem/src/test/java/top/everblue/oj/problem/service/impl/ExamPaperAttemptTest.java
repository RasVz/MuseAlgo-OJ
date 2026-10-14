package top.everblue.oj.problem.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.api.submission.SubmissionAttemptQuery;
import top.everblue.oj.problem.application.ExamAttemptApplicationService;
import top.everblue.oj.problem.application.ExamPaperStatsApplicationService;
import top.everblue.oj.problem.entity.ExamAnswer;
import top.everblue.oj.problem.entity.ExamAttempt;
import top.everblue.oj.problem.entity.ExamPaper;
import top.everblue.oj.problem.entity.ExamPaperProblem;
import top.everblue.oj.problem.entity.ExamQuestion;
import top.everblue.oj.problem.entity.dto.ExamAnswerSubmitDTO;
import top.everblue.oj.problem.entity.dto.ExamPaperDTO;
import top.everblue.oj.problem.entity.vo.ExamAttemptVO;
import top.everblue.oj.problem.mapper.ExamAnswerMapper;
import top.everblue.oj.problem.mapper.ExamAttemptMapper;
import top.everblue.oj.problem.mapper.ExamPaperMapper;
import top.everblue.oj.problem.mapper.ExamPaperProblemMapper;
import top.everblue.oj.problem.mapper.ExamQuestionMapper;
import top.everblue.oj.problem.mapper.ProblemMapper;

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
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

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

    private ExamPaper paper(int duration) {
        ExamPaper paper = new ExamPaper();
        paper.setId(10L);
        paper.setDurationMinutes(duration);
        return paper;
    }

    @Test
    void startAttemptUsesPaperDurationAsDeadline() {
        when(examPaperMapper.selectByIdForUpdate(10L)).thenReturn(paper(90));
        when(examAttemptMapper.selectOne(any())).thenReturn(null);
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
        when(examAttemptMapper.selectOne(any())).thenReturn(running);

        ExamAnswer a1 = new ExamAnswer();
        a1.setScore(5);
        ExamAnswer a2 = new ExamAnswer();
        a2.setScore(10);
        when(examAnswerMapper.selectList(any())).thenReturn(List.of(a1, a2));

        ExamPaperProblem p1 = new ExamPaperProblem();
        p1.setProblemId(100L);
        ExamPaperProblem p2 = new ExamPaperProblem();
        p2.setProblemId(200L);
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
    void autoFinalizeExpiredMarksTimedOut() {
        ExamAttempt expired = new ExamAttempt();
        expired.setId(2L);
        expired.setPaperId(10L);
        expired.setUserId(1L);
        expired.setStatus(ExamAttempt.STATUS_RUNNING);
        expired.setStartTime(LocalDateTime.now().minusHours(2));
        expired.setEndTime(LocalDateTime.now().minusMinutes(10));
        when(examAttemptMapper.selectList(any())).thenReturn(List.of(expired));
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
        when(examAttemptMapper.selectOne(any())).thenReturn(expired);
        when(examAnswerMapper.selectList(any())).thenReturn(List.of());
        when(examPaperProblemMapper.selectList(any())).thenReturn(List.of());

        ExamAttemptVO result = attemptFlow.finishAttempt(10L, 1L);

        assertEquals(ExamAttempt.STATUS_TIMEOUT, result.getStatus());
    }

    @Test
    void objectiveAnswersRequireRunningAttempt() {
        when(examAttemptMapper.selectOne(any())).thenReturn(null);
        ExamAnswerSubmitDTO submit = answerSubmit(1L, "A");

        assertThrows(IllegalStateException.class, () -> attemptFlow.submitAnswers(10L, 1L, submit));
    }

    @Test
    void objectiveAnswersBelongToCurrentAttempt() {
        ExamAttempt running = runningAttempt();
        when(examAttemptMapper.selectOne(any())).thenReturn(running);
        ExamQuestion question = new ExamQuestion();
        question.setId(1L);
        question.setPaperId(10L);
        question.setQuestionType("SINGLE");
        question.setAnswer("A");
        question.setScore(5);
        when(examQuestionMapper.selectList(any())).thenReturn(List.of(question));
        when(examAnswerMapper.selectOne(any())).thenReturn(null);
        when(examAnswerMapper.selectList(any())).thenReturn(List.of());

        attemptFlow.submitAnswers(10L, 1L, answerSubmit(1L, "A"));

        org.mockito.ArgumentCaptor<ExamAnswer> captor = org.mockito.ArgumentCaptor.forClass(ExamAnswer.class);
        verify(examAnswerMapper).insert(captor.capture());
        assertEquals(7L, captor.getValue().getAttemptId());
    }

    @Test
    void expiredAttemptIsFinalizedAndRejectsLateAnswers() {
        ExamAttempt expired = runningAttempt();
        expired.setEndTime(LocalDateTime.now().minusSeconds(1));
        when(examAttemptMapper.selectOne(any())).thenReturn(expired);
        when(examAnswerMapper.selectList(any())).thenReturn(List.of());
        when(examPaperProblemMapper.selectList(any())).thenReturn(List.of());

        assertThrows(IllegalStateException.class,
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
    void updatingPaperInvalidatesExistingAttemptsWithOldQuestions() {
        ExamPaper existing = paper(90);
        when(examPaperMapper.selectById(10L)).thenReturn(existing);
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
}
