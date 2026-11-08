package com.musealgo.oj.contest.application;

import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import com.musealgo.oj.contest.entity.Contest;
import com.musealgo.oj.contest.service.ContestService;
import com.musealgo.oj.common.security.AuthenticatedUser;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContestManagementApplicationServiceTest {
    @BeforeEach void login(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            (com.musealgo.oj.common.security.AuthenticatedUser) () -> 1L,null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));}
    @AfterEach void logout(){SecurityContextHolder.clearContext();}
    Contest command(String mode){var c=new Contest();c.setTitle("mode-test");c.setMode(mode);c.setStartTime(LocalDateTime.now().minusHours(1));c.setEndTime(LocalDateTime.now().plusHours(1));return c;}
    @Test void creatingOiContestPreservesRequestedMode() {
        var repository=mock(ContestService.class);when(repository.save(any(Contest.class))).thenReturn(true);
        var result=new ContestManagementApplicationService(repository, org.mockito.Mockito.mock(com.musealgo.oj.contest.service.ContestLevelService.class)).create(command("OI"),1L);
        assertEquals("OI",result.getMode());
    }

    @Test void titleEditKeepsOriginalEligibilitySnapshotAfterLevelChanges() {
        var repository = mock(ContestService.class);
        var levels = mock(com.musealgo.oj.contest.service.ContestLevelService.class);
        var existing = command("ACM");
        existing.setId(7L); existing.setCategory("FORMAL"); existing.setLevelCode("A");
        existing.setRatingMin(1000); existing.setRatingMax(1600); existing.setRegistrationReview(0);
        existing.setRankFinalizedAt(LocalDateTime.now());
        when(repository.getByIdForUpdate(7L)).thenReturn(existing);
        when(repository.updateById(any(Contest.class))).thenReturn(true);
        var changedLevel = new com.musealgo.oj.contest.entity.ContestLevel();
        changedLevel.setCode("A"); changedLevel.setMinRating(1800); changedLevel.setMaxRating(2400);
        when(levels.findEnabledByCode("A")).thenReturn(java.util.Optional.of(changedLevel));
        var edit = command("ACM"); edit.setId(7L); edit.setTitle("new title"); edit.setLevelCode("A");
        edit.setStartTime(existing.getStartTime()); edit.setEndTime(existing.getEndTime());

        new ContestManagementApplicationService(repository, levels).update(edit);

        assertEquals(1000, existing.getRatingMin());
        assertEquals(1600, existing.getRatingMax());
        verifyNoInteractions(levels);
    }

    @Test void sharedContestGuardRejectsStudentBeforeReadingContest() {
        var repository = mock(ContestService.class);
        var access = new ContestManagementApplicationService(repository,
                mock(com.musealgo.oj.contest.service.ContestLevelService.class));
        login(2L, "ROLE_STUDENT");

        assertThrows(com.musealgo.oj.common.exception.ForbiddenException.class,
                () -> access.requireManagedContest(7L));

        verifyNoInteractions(repository);
    }

    @Test void sharedContestGuardAllowsOwnerTeacherAndRejectsAnotherTeacher() {
        var repository = mock(ContestService.class);
        var access = new ContestManagementApplicationService(repository,
                mock(com.musealgo.oj.contest.service.ContestLevelService.class));
        var owned = new Contest(); owned.setId(7L); owned.setAuthorId(1L);
        when(repository.getByIdForUpdate(7L)).thenReturn(owned);

        login(1L, "ROLE_TEACHER");
        assertSame(owned, access.requireManagedContest(7L));

        login(2L, "ROLE_TEACHER");
        assertThrows(com.musealgo.oj.common.exception.ForbiddenException.class,
                () -> access.requireManagedContest(7L));
        verify(repository, times(2)).getByIdForUpdate(7L);
    }

    @Test void sharedContestGuardAllowsAdminRegardlessOfAuthor() {
        var repository = mock(ContestService.class);
        var access = new ContestManagementApplicationService(repository,
                mock(com.musealgo.oj.contest.service.ContestLevelService.class));
        var owned = new Contest(); owned.setId(7L); owned.setAuthorId(99L);
        when(repository.getByIdForUpdate(7L)).thenReturn(owned);
        login(1L, "ROLE_ADMIN");

        assertSame(owned, access.requireManagedContest(7L));
    }

    private static void login(Long userId, String role) {
        AuthenticatedUser principal = () -> userId;
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(role))));
    }
    @Test void unknownFormalLevelIsAnExplicitRequestRejection() {
        var repository=mock(ContestService.class);var levels=mock(com.musealgo.oj.contest.service.ContestLevelService.class);
        login(1L,"ROLE_ADMIN");
        var input=new Contest();input.setTitle("synthetic");input.setCategory("FORMAL");input.setLevelCode("missing");
        input.setStartTime(LocalDateTime.now().plusDays(1));input.setEndTime(LocalDateTime.now().plusDays(2));input.setDuration(60);
        var failure=assertThrows(com.musealgo.oj.common.exception.BusinessException.class,
                ()->new ContestManagementApplicationService(repository,levels).create(input,1L));
        assertEquals("CONTEST_LEVEL_NOT_FOUND",failure.getErrorCode().key());
    }

}
