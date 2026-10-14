package top.everblue.oj.judge.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import top.everblue.oj.api.contest.ContestSubmissionContextResolver;
import top.everblue.oj.judge.entity.Submission;
import top.everblue.oj.judge.service.SubmissionService;
import top.everblue.oj.system.entity.User;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionApplicationServiceTest {

    @Mock SubmissionService submissionService;
    @Mock ContestSubmissionContextResolver contextResolver;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void contestSubmissionUsesServerResolvedVpAttempt() {
        login(10L);
        SubmitSolutionCommand command = new SubmitSolutionCommand();
        command.setProblemId(100L);
        command.setContestId(1L);
        command.setLanguage("cpp");
        command.setCode("int main(){}");
        when(contextResolver.resolve(1L, 100L, 10L))
                .thenReturn(new ContestSubmissionContextResolver.SubmissionContext(1L, 9L));
        when(submissionService.submitCode(any())).thenAnswer(invocation -> {
            Submission submission = invocation.getArgument(0);
            submission.setId(5L);
            return submission;
        });

        new SubmissionApplicationService(submissionService, contextResolver).submit(command);

        ArgumentCaptor<Submission> captor = ArgumentCaptor.forClass(Submission.class);
        verify(submissionService).submitCode(captor.capture());
        assertEquals(1L, captor.getValue().getContestId());
        assertEquals(9L, captor.getValue().getVpAttemptId());
        assertEquals(10L, captor.getValue().getUserId());
    }

    private void login(Long userId) {
        User user = new User();
        user.setId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
