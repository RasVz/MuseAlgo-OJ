package com.musealgo.oj.problem.application;

import com.musealgo.oj.api.submission.SubmissionAttemptQuery;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.problem.entity.ExamAnswer;
import com.musealgo.oj.problem.entity.ExamAttempt;
import com.musealgo.oj.problem.entity.ExamPaper;
import com.musealgo.oj.problem.entity.ExamPaperProblem;
import com.musealgo.oj.problem.entity.ExamQuestion;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.ProblemSample;
import com.musealgo.oj.problem.entity.vo.ExamPaperAnswerProblemVO;
import com.musealgo.oj.problem.entity.vo.ExamPaperAnswerVO;
import com.musealgo.oj.problem.mapper.ExamAnswerMapper;
import com.musealgo.oj.problem.mapper.ExamAttemptMapper;
import com.musealgo.oj.problem.mapper.ExamPaperMapper;
import com.musealgo.oj.problem.mapper.ExamPaperProblemMapper;
import com.musealgo.oj.problem.mapper.ExamQuestionMapper;
import com.musealgo.oj.problem.mapper.ProblemMapper;
import com.musealgo.oj.problem.mapper.ProblemSampleMapper;
import com.musealgo.oj.common.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExamPaperAnswerApplicationServiceTest {

    @Mock private ExamPaperMapper examPaperMapper;
    @Mock private ExamPaperProblemMapper examPaperProblemMapper;
    @Mock private ExamQuestionMapper examQuestionMapper;
    @Mock private ExamAnswerMapper examAnswerMapper;
    @Mock private ExamAttemptMapper examAttemptMapper;
    @Mock private ProblemMapper problemMapper;
    @Mock private ProblemSampleMapper problemSampleMapper;
    @Mock private SubmissionAttemptQuery submissionAttemptQuery;

    @InjectMocks private ExamPaperAnswerApplicationService service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void paperContextMasksObjectiveAnswersAndReturnsCodingSummary() {
        login(1L);
        ExamAttempt attempt = attempt(7L, 10L, 1L, ExamAttempt.STATUS_RUNNING);
        attempt.setScoringVersion(ExamAttempt.SCORING_BOUND);
        when(examAttemptMapper.selectById(7L)).thenReturn(attempt);
        when(examPaperMapper.selectById(10L)).thenReturn(paper(10L));
        when(examQuestionMapper.selectList(any())).thenReturn(List.of(question()));
        when(examAnswerMapper.selectList(any())).thenReturn(List.of(answer()));
        when(examPaperProblemMapper.selectList(any())).thenReturn(List.of(relation()));
        when(problemMapper.selectBatchIds(any())).thenReturn(List.of(problem()));
        when(submissionAttemptQuery.summarizeByAttempt(eq(7L), any())).thenReturn(List.of(
                new SubmissionAttemptQuery.SubmissionAttemptSummary(2001L, 100, 2, 0)));

        ExamPaperAnswerVO vo = service.paper(10L, 7L);

        assertEquals("作答中", statusText(vo));
        assertEquals(1, vo.getObjectiveQuestions().size());
        ExamPaperAnswerVO.ObjectiveQuestionVO objective = vo.getObjectiveQuestions().get(0);
        assertEquals("A", objective.getMyAnswer());
        assertEquals(true, objective.getCorrect());
        assertEquals(5, objective.getGainedScore());
        assertEquals(1, vo.getCodingProblems().size());
        assertEquals("SOLVED", vo.getCodingProblems().get(0).getMyStatus());
        assertEquals(100, vo.getCodingProblems().get(0).getMyBestScore());
        assertTrue(vo.getCanSubmit());
        assertFalse(vo.getGradingPending());
    }

    @Test
    void problemContextReturnsStatementSamplesButNoDifficultyOrHint() {
        login(1L);
        ExamAttempt attempt = attempt(7L, 10L, 1L, ExamAttempt.STATUS_RUNNING);
        when(examAttemptMapper.selectById(7L)).thenReturn(attempt);
        when(examPaperMapper.selectById(10L)).thenReturn(paper(10L));
        when(examPaperProblemMapper.selectOne(any())).thenReturn(relation());
        Problem problem = problem();
        when(problemMapper.selectById(2001L)).thenReturn(problem);
        ProblemSample sample = new ProblemSample();
        sample.setProblemId(2001L);
        sample.setSampleOrder(1);
        sample.setInput("1 2");
        sample.setOutput("3");
        when(problemSampleMapper.selectList(any())).thenReturn(List.of(sample));
        when(submissionAttemptQuery.summarizeByAttempt(eq(7L), any())).thenReturn(List.of());

        ExamPaperAnswerProblemVO vo = service.problem(10L, 7L, 2001L);

        assertEquals("A + B", vo.getTitle());
        assertEquals("输入两个整数，输出它们的和。", vo.getContent());
        assertEquals(1, vo.getSamples().size());
        assertEquals("1 2", vo.getSamples().get(0).getInput());
        assertEquals(50, vo.getScore());
        assertTrue(vo.getCanSubmit());
        // VO 不包含 difficulty/tags/hint 字段，编译期即保证不会下发。
    }

    @Test
    void anotherUsersAttemptIsRejected() {
        login(2L);
        when(examAttemptMapper.selectById(7L)).thenReturn(attempt(7L, 10L, 1L, ExamAttempt.STATUS_RUNNING));

        assertThrows(ForbiddenException.class, () -> service.paper(10L, 7L));
    }

    @Test
    void expiredAttemptIsReadOnly() {
        login(1L);
        ExamAttempt attempt = attempt(7L, 10L, 1L, ExamAttempt.STATUS_RUNNING);
        attempt.setEndTime(LocalDateTime.now().minusSeconds(1));
        when(examAttemptMapper.selectById(7L)).thenReturn(attempt);
        when(examPaperMapper.selectById(10L)).thenReturn(paper(10L));
        when(examPaperProblemMapper.selectList(any())).thenReturn(List.of());
        when(examQuestionMapper.selectList(any())).thenReturn(List.of());

        ExamPaperAnswerVO vo = service.paper(10L, 7L);

        assertEquals("TIMEOUT", vo.getStatus());
        assertEquals(0L, vo.getRemainingSeconds());
        assertFalse(vo.getCanSubmit());
    }

    private static String statusText(ExamPaperAnswerVO vo) {
        return "RUNNING".equals(vo.getStatus()) ? "作答中" : vo.getStatus();
    }

    private ExamAttempt attempt(Long id, Long paperId, Long userId, String status) {
        ExamAttempt attempt = new ExamAttempt();
        attempt.setId(id);
        attempt.setPaperId(paperId);
        attempt.setUserId(userId);
        attempt.setStatus(status);
        attempt.setScoringVersion(ExamAttempt.SCORING_BOUND);
        attempt.setStartTime(LocalDateTime.now().minusMinutes(10));
        attempt.setEndTime(LocalDateTime.now().plusMinutes(50));
        return attempt;
    }

    private ExamPaper paper(Long id) {
        ExamPaper paper = new ExamPaper();
        paper.setId(id);
        paper.setTitle("合成整卷");
        paper.setDescription("说明");
        paper.setIsPublished(1);
        return paper;
    }

    private ExamQuestion question() {
        ExamQuestion question = new ExamQuestion();
        question.setId(1001L);
        question.setPaperId(10L);
        question.setQuestionType(ExamQuestion.TYPE_SINGLE);
        question.setStatement("单选题");
        question.setOptions("[{\"key\":\"A\",\"text\":\"选项A\"}]");
        question.setAnswer("A");
        question.setExplanation("解析");
        question.setScore(5);
        question.setQuestionOrder(1);
        return question;
    }

    private ExamAnswer answer() {
        ExamAnswer answer = new ExamAnswer();
        answer.setQuestionId(1001L);
        answer.setAnswer("A");
        answer.setIsCorrect(1);
        answer.setScore(5);
        return answer;
    }

    private ExamPaperProblem relation() {
        ExamPaperProblem relation = new ExamPaperProblem();
        relation.setPaperId(10L);
        relation.setProblemId(2001L);
        relation.setProblemOrder(1);
        relation.setScore(50);
        return relation;
    }

    private Problem problem() {
        Problem problem = new Problem();
        problem.setId(2001L);
        problem.setTitle("A + B");
        problem.setContent("输入两个整数，输出它们的和。");
        problem.setInputDesc("两个整数");
        problem.setOutputDesc("和");
        problem.setHint("这是提示，不应下发");
        problem.setDifficulty(3);
        problem.setTimeLimit(1000);
        problem.setMemoryLimit(256);
        return problem;
    }

    private void login(Long userId) {
        AuthenticatedUser user = () -> userId;
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
