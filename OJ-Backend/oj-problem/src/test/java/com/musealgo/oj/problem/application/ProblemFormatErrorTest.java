package com.musealgo.oj.problem.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musealgo.oj.api.ai.AiAssistant;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.problem.format.ProblemFormatRequest;
import com.musealgo.oj.problem.infrastructure.ProblemSearchIndexer;
import com.musealgo.oj.problem.service.ProblemService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ProblemFormatErrorTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void perItemPermissionRejectionKeepsItsPublicReason() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "teacher", null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
        var access = mock(ProblemAccessGuard.class);
        when(access.requireManagedProblem(anyLong())).thenThrow(new ForbiddenException("无权操作该资源"));
        var ai = mock(AiAssistant.class);
        var service = new ProblemFormatApplicationService(mock(ProblemService.class), new ObjectMapper(), ai,
                mock(PlatformTransactionManager.class), mock(ProblemSearchIndexer.class), access, mock(ProblemSampleApplicationService.class));
        var preview = service.previewFormatProblem(7L, false);
        assertFalse(preview.success());
        assertEquals("ACCESS_DENIED", preview.errorCode());
        assertEquals("无权操作该资源", preview.message());
        var batch = service.formatProblems(new ProblemFormatRequest(List.of(7L)));
        assertEquals("ACCESS_DENIED", batch.items().get(0).errorCode());
        verifyNoInteractions(ai);
    }
}
