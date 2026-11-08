package com.musealgo.oj.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.common.exception.CommonErrorCode;
import com.musealgo.oj.community.controller.AdminAnnouncementController;
import com.musealgo.oj.community.entity.Post;
import com.musealgo.oj.community.service.PostService;
import com.musealgo.oj.problem.application.ProblemListAccessService;
import com.musealgo.oj.problem.controller.ProblemListController;
import com.musealgo.oj.problem.controller.ProblemListItemController;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.ProblemList;
import com.musealgo.oj.problem.service.*;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.system.service.UserService;
import com.musealgo.oj.teaching.application.ClassMembershipApplicationService;
import com.musealgo.oj.teaching.controller.ClassCoursewareController;
import com.musealgo.oj.teaching.entity.ClassCourseware;
import com.musealgo.oj.teaching.service.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WriteFailureContractTest {
    private final ProblemListService lists = mock(ProblemListService.class);
    private final ProblemListItemService items = mock(ProblemListItemService.class);
    private final ProblemService problems = mock(ProblemService.class);
    private final ProblemListItemController itemController = new ProblemListItemController(items, problems, lists, mock(ProblemListAccessService.class), mock(com.musealgo.oj.problem.application.ProblemReadAccessService.class));
    private final ProblemListController listController = new ProblemListController(mock(ProblemListAccessService.class), null, null, lists);

    @BeforeEach void login() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(),"write-error-test"),ClassCourseware.class);
        User actor = new User(); actor.setId(7L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                actor, null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private ProblemList ownedList() {
        ProblemList list = new ProblemList(); list.setId(3L); list.setCreatorId(7L); list.setType((byte)1);
        when(lists.getById(3L)).thenReturn(list);
        return list;
    }
    private void existingProblem() {
        ownedList(); Problem problem = new Problem(); problem.setId(11L);
        when(problems.getOne(any())).thenReturn(problem);
    }

    @Test void failedItemInsertCannotReturnSuccessOrChangeTheCount() {
        existingProblem(); when(items.save(any())).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> itemController.addProblem(Map.of("problemListId",3,"problemDisplayId","11")));
        verify(lists, never()).update(any());
    }
    @Test void failedCountIncrementAbortsTheItemOperation() {
        existingProblem(); when(items.save(any())).thenReturn(true); when(lists.update(any())).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> itemController.addProblem(Map.of("problemListId",3,"problemDisplayId","11")));
    }
    @Test void missingListItemIsNotBadInput() {
        ownedList(); when(items.remove(any())).thenReturn(false);
        BusinessException error = assertThrows(BusinessException.class, () -> itemController.removeProblem(Map.of("problemListId",3,"problemId",11)));
        assertEquals("PROBLEM_LIST_ITEM_NOT_FOUND", error.getErrorCode().key());
        assertEquals(404,error.getErrorCode().status()); verify(lists, never()).update(any());
    }
    @Test void failedCountDecrementAbortsRemoval() {
        ownedList(); when(items.remove(any())).thenReturn(true); when(lists.update(any())).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> itemController.removeProblem(Map.of("problemListId",3,"problemId",11)));
    }
    @Test void failedListCreateCannotReturnAnEmptySuccessfulId() {
        ProblemList input = new ProblemList(); input.setTitle("Synthetic list");
        assertThrows(IllegalStateException.class, () -> listController.add(input));
    }
    @Test void failedListEditCannotReturnSuccess() {
        ownedList(); ProblemList input = new ProblemList(); input.setId(3L); input.setTitle("Updated");
        assertThrows(IllegalStateException.class, () -> listController.update(input));
    }
    @Test void failedListDeleteCannotReturnSuccess() {
        ownedList(); when(lists.removeById(3L)).thenReturn(false);
        assertNotEquals(200,listController.delete(3L).getCode());
    }
    @Test void missingAndForeignListsHaveDistinctSafeRejections() {
        assertEquals("PROBLEM_LIST_NOT_FOUND",listController.delete(3L).getErrorCode());
        ownedList().setCreatorId(8L);
        assertEquals("ACCESS_DENIED",listController.delete(3L).getErrorCode());
        verify(lists,never()).removeById(3L);
    }
    @Test void successfulItemAddAndRemoveKeepTheirSuccessContract() {
        existingProblem(); when(items.save(any())).thenReturn(true); when(items.remove(any())).thenReturn(true); when(lists.update(any())).thenReturn(true);
        assertEquals(200,itemController.addProblem(Map.of("problemListId",3,"problemDisplayId","11")).getCode());
        assertEquals(200,itemController.removeProblem(Map.of("problemListId",3,"problemId",11)).getCode());
    }
    @Test void foreignListIsRejectedBeforeAnyItemWrite() {
        ownedList().setCreatorId(8L);
        assertEquals("ACCESS_DENIED",itemController.addProblem(Map.of("problemListId",3,"problemDisplayId","11")).getErrorCode());
        verifyNoInteractions(items,problems);
    }

    @Test void announcementInsertAndEditFailuresDoNotReturnSuccess() {
        PostService posts=mock(PostService.class); var controller=new AdminAnnouncementController(posts);
        Post input=new Post(); input.setTitle("Synthetic announcement"); input.setContent("Synthetic content");
        assertThrows(IllegalStateException.class,()->controller.save(input));
        input.setId(3L);
        assertNotEquals(200,controller.save(input).getCode());
    }
    @Test void announcementMissingDeleteDoesNotClaimDeletion() {
        var controller=new AdminAnnouncementController(mock(PostService.class));
        assertEquals("POST_NOT_FOUND",controller.delete(999L).getErrorCode());
    }
    @Test void aFailedBatchSortItemMustFailTheWholeOperation() {
        var relations=mock(ClassCoursewareService.class);
        ClassCourseware relation=new ClassCourseware(); relation.setId(3L); relation.setClassId(2L);
        when(relations.getById(3L)).thenReturn(relation);
        var controller=new ClassCoursewareController(relations,mock(CoursewareService.class),mock(ClassMembershipApplicationService.class));
        when(relations.update(any())).thenReturn(false);
        assertThrows(IllegalStateException.class,()->controller.updateSortBatch(List.of(Map.of("id",3,"sort",4))));
    }
    @Test void preferenceDependencyFailureMustReachTheAvailabilityHandler() {
        var users=mock(UserService.class);var controller=new UserController();
        ReflectionTestUtils.setField(controller,"userService",users);ReflectionTestUtils.setField(controller,"objectMapper",new ObjectMapper());
        var failure=new DataAccessResourceFailureException("synthetic database unavailable");
        when(users.updatePreferences(any(),any())).thenThrow(failure);
        assertSame(failure,assertThrows(DataAccessResourceFailureException.class,()->controller.updatePreferences(Map.of("reports",false))));
    }
}
