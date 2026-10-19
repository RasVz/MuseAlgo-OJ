package top.everblue.oj.contest.application;

import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.service.ContestService;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContestManagementApplicationServiceTest {
    @BeforeEach void login(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "admin",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));}
    @AfterEach void logout(){SecurityContextHolder.clearContext();}
    Contest command(String mode){var c=new Contest();c.setTitle("mode-test");c.setMode(mode);c.setStartTime(LocalDateTime.now().minusHours(1));c.setEndTime(LocalDateTime.now().plusHours(1));return c;}
    @Test void creatingOiContestPreservesRequestedMode() {
        var repository=mock(ContestService.class);when(repository.save(any(Contest.class))).thenReturn(true);
        var result=new ContestManagementApplicationService(repository).create(command("OI"),1L);
        assertEquals("OI",result.getMode());
    }
}
