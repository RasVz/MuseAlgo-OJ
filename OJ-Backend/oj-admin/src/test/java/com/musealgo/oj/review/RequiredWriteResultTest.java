package com.musealgo.oj.review;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.musealgo.oj.api.user.*;
import com.musealgo.oj.contest.entity.*;
import com.musealgo.oj.contest.mapper.*;
import com.musealgo.oj.contest.service.ContestEligibilityService;
import com.musealgo.oj.contest.service.impl.ContestUserServiceImpl;
import com.musealgo.oj.system.infrastructure.UserRatingCommandAdapter;
import com.musealgo.oj.system.service.UserService;
import com.musealgo.oj.community.entity.UserRelation;
import com.musealgo.oj.community.mapper.UserRelationMapper;
import com.musealgo.oj.community.service.impl.UserRelationServiceImpl;
import com.musealgo.oj.teaching.mapper.ClassStudentMapper;
import com.musealgo.oj.teaching.service.impl.ClassServiceImpl;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RequiredWriteResultTest {
    @BeforeAll static void metadata() {
        for (var entity : new java.lang.Class<?>[]{Contest.class, ContestUser.class, UserRelation.class}) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "review"), entity);
        }
    }
    @Test void ratingBatchMustNotReportSuccessWhenAUserWriteFails() {
        var users = mock(UserService.class);
        assertThrows(RuntimeException.class, () -> new UserRatingCommandAdapter(users).batchUpdateRatings(Map.of(7L, 1500)));
    }
    @Test void failedRegistrationDoesNotIncrementParticipantCount() {
        var mapper = mock(ContestMapper.class); var rows = mock(ContestUserMapper.class);
        var service = registrationService(mapper, rows);
        assertThrows(RuntimeException.class, () -> service.registerContest(1L, 7L));
        verify(mapper, never()).update(any(Contest.class), any());
    }
    @Test void failedParticipantCounterMustAbortRegistration() {
        var mapper = mock(ContestMapper.class); var rows = mock(ContestUserMapper.class);
        var service = registrationService(mapper, rows);
        when(rows.insert(any(ContestUser.class))).thenReturn(1);
        assertThrows(RuntimeException.class, () -> service.registerContest(1L, 7L));
    }
    private ContestUserServiceImpl registrationService(ContestMapper mapper, ContestUserMapper rows) {
        var contest = new Contest(); contest.setId(1L); contest.setVisible(1); contest.setIsDeleted(0);
        when(mapper.selectByIdForUpdate(1L)).thenReturn(contest);
        var service = new ContestUserServiceImpl(mapper, mock(UserQuery.class), mock(ContestEligibilityService.class));
        ReflectionTestUtils.setField(service, "baseMapper", rows); return service;
    }
    @Test void failedReviewWriteMustNotReportApproval() {
        var rows = mock(ContestUserMapper.class);
        var service = new ContestUserServiceImpl(mock(ContestMapper.class), mock(UserQuery.class), mock(ContestEligibilityService.class));
        ReflectionTestUtils.setField(service, "baseMapper", rows);
        var record = new ContestUser(); record.setId(1L); record.setStatus("PENDING");
        when(rows.selectOne(any(), anyBoolean())).thenReturn(record);
        assertThrows(RuntimeException.class, () -> service.reviewRegistration(1L, 7L, true, null, 3L));
    }
    @Test @SuppressWarnings("unchecked") void failedClassMembershipInsertMustNotReportJoined() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> codes = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(codes); when(codes.get("invite:code:123456")).thenReturn("1");
        var rows = mock(ClassStudentMapper.class);
        var service=new ClassServiceImpl(redis,rows);var mapper=mock(com.musealgo.oj.teaching.mapper.ClassMapper.class);
        when(mapper.selectByIdForUpdate(1L)).thenReturn(new com.musealgo.oj.teaching.entity.Class());
        ReflectionTestUtils.setField(service,"baseMapper",mapper);
        assertThrows(RuntimeException.class, () -> service.joinByInviteCode(7L, "123456"));
    }
    @Test void concurrentUnfollowDoesNotDecrementCountersTwice() {
        var rows = mock(UserRelationMapper.class); var counts = mock(UserStatsCommand.class);
        var relation = new UserRelation(); relation.setId(1L);
        when(rows.selectOne(any())).thenReturn(relation);
        when(rows.deleteById(1L)).thenReturn(0);
        assertEquals("unfollowed", new UserRelationServiceImpl(rows, counts).toggleFollow(7L, 8L));
        verifyNoInteractions(counts);
    }
    @Test void racingFollowHasKnownConflictInsteadOfInternalError() {
        var rows=mock(UserRelationMapper.class);
        when(rows.insert(any(UserRelation.class))).thenThrow(new org.springframework.dao.DuplicateKeyException("synthetic unique follow"));
        var error=assertThrows(com.musealgo.oj.common.exception.BusinessException.class,()->new UserRelationServiceImpl(rows,mock(UserStatsCommand.class)).toggleFollow(7L,8L));
        assertEquals(409,error.getErrorCode().status());
    }
    @Test void failedFollowInsertDoesNotIncrementCounters() {
        var rows = mock(UserRelationMapper.class); var counts = mock(UserStatsCommand.class);
        assertThrows(RuntimeException.class, () -> new UserRelationServiceImpl(rows, counts).toggleFollow(7L, 8L));
        verifyNoInteractions(counts);
    }
    @Test void classProblemListWritesMustNotPretendSuccess() {
        var lists = mock(com.musealgo.oj.problem.service.ProblemListService.class);
        var service = new com.musealgo.oj.problem.application.ProblemListCommandService(lists);
        assertThrows(RuntimeException.class, () -> service.createClassProblemList(
                new com.musealgo.oj.api.problem.ProblemListCommand.NewClassProblemList(1L, 2L, "synthetic", null)));
        assertThrows(RuntimeException.class, () -> service.updateClassProblemList(1L, "synthetic", null));
        assertThrows(RuntimeException.class, () -> service.removeById(1L));
        assertThrows(RuntimeException.class, () -> service.adjustFavoriteCount(1L, 1));
    }

}
