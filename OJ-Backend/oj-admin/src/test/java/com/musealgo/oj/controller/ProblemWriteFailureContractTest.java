package com.musealgo.oj.controller;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.problem.application.ProblemAccessGuard;
import com.musealgo.oj.problem.application.ProblemCaseApplicationService;
import com.musealgo.oj.problem.application.ProblemManagementApplicationService;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.dto.ProblemDTO;
import com.musealgo.oj.problem.error.ProblemErrorCode;
import com.musealgo.oj.problem.service.ProblemCaseService;
import com.musealgo.oj.problem.service.ProblemSampleService;
import com.musealgo.oj.problem.service.ProblemService;
import com.musealgo.oj.system.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 题目写入未生效时必须返回可定位的业务码，而不是落进全局兜底的 500「系统暂时不可用」。
 * 触发方式：mock 持久层返回 false（等价于 affected rows = 0）。
 */
class ProblemWriteFailureContractTest {

    private final ProblemService problems = mock(ProblemService.class);
    private final ProblemCaseService cases = mock(ProblemCaseService.class);
    private final ProblemSampleService samples = mock(ProblemSampleService.class);
    private final ProblemAccessGuard guard = mock(ProblemAccessGuard.class);
    private final ProblemCaseApplicationService caseApp = mock(ProblemCaseApplicationService.class);
    private final ProblemManagementApplicationService service = new ProblemManagementApplicationService(
            null, problems, cases, samples, null, null, null, guard, caseApp, null, null, null, null);

    @BeforeEach
    void login() {
        User actor = new User();
        actor.setId(7L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                actor, null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void failedCreateReturnsExplicitWriteFailure() {
        when(problems.save(any())).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(new ProblemDTO(), 7L));

        assertEquals(ProblemErrorCode.PROBLEM_WRITE_FAILED, error.getErrorCode());
        assertEquals(500, error.getErrorCode().status());
    }

    @Test
    void failedUpdateReturnsExplicitWriteFailure() {
        Problem existing = new Problem();
        existing.setId(11L);
        when(guard.requireManagedProblem(11L)).thenReturn(existing);
        when(problems.updateById(any())).thenReturn(false);

        ProblemDTO command = new ProblemDTO();
        command.setId(11L);
        BusinessException error = assertThrows(BusinessException.class, () -> service.update(command));

        assertEquals(ProblemErrorCode.PROBLEM_WRITE_FAILED, error.getErrorCode());
    }

    @Test
    void failedDeleteReturnsExplicitWriteFailure() {
        Problem existing = new Problem();
        existing.setId(11L);
        when(guard.requireManagedProblem(11L)).thenReturn(existing);
        when(problems.removeById(11L)).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class, () -> service.delete(11L));

        assertEquals(ProblemErrorCode.PROBLEM_WRITE_FAILED, error.getErrorCode());
    }
}
