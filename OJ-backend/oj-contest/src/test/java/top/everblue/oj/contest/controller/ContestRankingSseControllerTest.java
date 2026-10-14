package top.everblue.oj.contest.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import top.everblue.oj.common.exception.ForbiddenException;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.service.ContestService;
import top.everblue.oj.contest.sse.SseRegistry;
import top.everblue.oj.system.entity.User;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContestRankingSseControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void studentCannotSubscribeToRunningOiRanking() {
        User user = new User();
        user.setId(10L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        SseRegistry registry = mock(SseRegistry.class);
        ContestService contestService = mock(ContestService.class);
        Contest contest = new Contest();
        contest.setMode("OI");
        contest.setStartTime(LocalDateTime.now().minusMinutes(10));
        contest.setEndTime(LocalDateTime.now().plusMinutes(10));
        when(contestService.getById(1L)).thenReturn(contest);

        ContestRankingSseController controller =
                new ContestRankingSseController(registry, contestService);

        assertThrows(ForbiddenException.class, () -> controller.stream(1L));
        verify(registry, never()).register(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
