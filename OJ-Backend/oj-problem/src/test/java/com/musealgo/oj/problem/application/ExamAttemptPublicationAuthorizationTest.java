package com.musealgo.oj.problem.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import com.musealgo.oj.api.submission.SubmissionAttemptQuery;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.common.security.AuthenticatedUser;
import com.musealgo.oj.problem.entity.ExamPaper;
import com.musealgo.oj.problem.entity.dto.ExamAnswerSubmitDTO;
import com.musealgo.oj.problem.mapper.ExamAnswerMapper;
import com.musealgo.oj.problem.mapper.ExamAttemptMapper;
import com.musealgo.oj.problem.mapper.ExamPaperMapper;
import com.musealgo.oj.problem.mapper.ExamPaperProblemMapper;
import com.musealgo.oj.problem.mapper.ExamQuestionMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExamAttemptPublicationAuthorizationTest {

    @Mock private ExamPaperMapper examPaperMapper;
    @Mock private ExamPaperProblemMapper examPaperProblemMapper;
    @Mock private ExamQuestionMapper examQuestionMapper;
    @Mock private ExamAnswerMapper examAnswerMapper;
    @Mock private ExamAttemptMapper examAttemptMapper;
    @Mock private SubmissionAttemptQuery submissionAttemptQuery;
    @InjectMocks private ExamAttemptApplicationService service;

    @BeforeEach
    void loginAsStudent() {
        login(41L, "ROLE_STUDENT");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unpublishedPaperCannotBeStartedSubmittedReadOrFinishedByStudent() {
        lenient().when(examPaperMapper.selectById(9L)).thenReturn(draftPaper());
        when(examPaperMapper.selectByIdForUpdate(9L)).thenReturn(draftPaper());
        ExamAnswerSubmitDTO submit = new ExamAnswerSubmitDTO();
        ExamAnswerSubmitDTO.AnswerItem item = new ExamAnswerSubmitDTO.AnswerItem();
        item.setQuestionId(501L);
        item.setAnswer("A");
        submit.setAnswers(List.of(item));

        assertThrows(ForbiddenException.class, () -> service.startAttempt(9L, 41L));
        assertThrows(ForbiddenException.class, () -> service.submitAnswers(9L, 41L, submit));
        assertThrows(ForbiddenException.class, () -> service.getAttempt(9L, 41L));
        assertThrows(ForbiddenException.class, () -> service.finishAttempt(9L, 41L));

        verify(examAttemptMapper, never()).insert(org.mockito.ArgumentMatchers.any(
                com.musealgo.oj.problem.entity.ExamAttempt.class));
        verify(examAnswerMapper, never()).insert(org.mockito.ArgumentMatchers.any(
                com.musealgo.oj.problem.entity.ExamAnswer.class));
    }

    @Test
    void administratorMayStartAnUnpublishedPaper() {
        login(2L, "ROLE_ADMIN");
        when(examPaperMapper.selectByIdForUpdate(9L)).thenReturn(draftPaper());
        when(examAttemptMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            ((com.musealgo.oj.problem.entity.ExamAttempt) invocation.getArgument(0)).setId(12L);
            return 1;
        }).when(examAttemptMapper).insert(org.mockito.ArgumentMatchers.any(
                com.musealgo.oj.problem.entity.ExamAttempt.class));

        assertDoesNotThrow(() -> service.startAttempt(9L, 2L));
    }

    private static ExamPaper draftPaper() {
        ExamPaper paper = new ExamPaper();
        paper.setId(9L);
        paper.setIsPublished(0);
        paper.setDurationMinutes(60);
        return paper;
    }

    private static void login(Long userId, String role) {
        AuthenticatedUser principal = () -> userId;
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(role))));
    }
}
