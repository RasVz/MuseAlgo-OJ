package top.everblue.oj.problem.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.everblue.oj.api.contest.ContestProblemAccessQuery;
import top.everblue.oj.api.teaching.ClassQuery;
import top.everblue.oj.common.exception.ForbiddenException;
import top.everblue.oj.common.security.AuthenticatedUser;
import top.everblue.oj.problem.entity.Problem;
import top.everblue.oj.problem.mapper.ProblemListItemMapper;
import top.everblue.oj.problem.service.ProblemService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProblemReadAccessServiceTest {
    final ProblemService problems = mock(ProblemService.class);
    final ProblemListItemMapper lists = mock(ProblemListItemMapper.class);
    final ClassQuery classes = mock(ClassQuery.class);
    final ContestProblemAccessQuery contests = mock(ContestProblemAccessQuery.class);
    final ProblemReadAccessService access = new ProblemReadAccessService(problems, lists, classes, contests);
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    Problem problem(boolean open) {
        Problem p = new Problem(); p.setId(1L); p.setAuthorId(7L); p.setIsPublic(open);
        when(problems.getById(1L)).thenReturn(p); return p;
    }
    void login(long id, String role) {
        AuthenticatedUser actor = () -> id;
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
    @Test void anonymousCanReadPublicButNotPrivate() {
        Problem p = problem(true); assertDoesNotThrow(() -> access.requireReadable(1L));
        p.setIsPublic(false); assertThrows(ForbiddenException.class, () -> access.requireReadable(1L));
    }
    @Test void privateOwnerAndAdminCanRead() {
        problem(false); login(7L, "TEACHER"); assertDoesNotThrow(() -> access.requireReadable(1L));
        login(8L, "ADMIN"); assertDoesNotThrow(() -> access.requireReadable(1L));
    }
    @Test void otherTeacherIsNotAutomaticallyAnOwner() {
        problem(false); login(8L, "TEACHER"); assertThrows(ForbiddenException.class, () -> access.requireReadable(1L));
    }
    @Test void classAssignmentGrantsAccessOnlyToItsMembers() {
        problem(false); when(lists.selectAssignedClassIds(1L)).thenReturn(List.of(3L));
        when(classes.findById(3L)).thenReturn(java.util.Optional.of(new ClassQuery.ClassInfo(3L, "class", 7L)));
        when(classes.findStudentIds(3L)).thenReturn(List.of(10L));
        login(10L, "STUDENT"); assertDoesNotThrow(() -> access.requireReadable(1L));
        login(11L, "STUDENT"); assertThrows(ForbiddenException.class, () -> access.requireReadable(1L));
    }
    @Test void contestPermissionGrantsReadButActiveContestCannotBeSubmittedAsPractice() {
        problem(false); login(10L, "STUDENT"); when(contests.canReadProblem(1L, 10L)).thenReturn(true);
        assertDoesNotThrow(() -> access.requireReadable(1L));
        when(contests.hasRunningContest(1L, 10L)).thenReturn(true);
        assertThrows(ForbiddenException.class, () -> access.requirePracticeSubmission(1L));
    }
    @Test void deletedProblemCannotBeReadEvenByAdmin() {
        problem(true).setIsDeleted(1); login(8L, "ADMIN");
        assertThrows(IllegalArgumentException.class, () -> access.requireReadable(1L));
    }
    @Test void publicListSerializationNeverIncludesStandardAnswer() throws Exception {
        var p = new top.everblue.oj.problem.entity.vo.ProblemVO(); p.setStdAnswer("SECRET-CODE"); p.setStdExplanation("SECRET-EXPLANATION");
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(p);
        assertFalse(json.contains("SECRET")); assertFalse(json.contains("stdAnswer"));
    }
}
