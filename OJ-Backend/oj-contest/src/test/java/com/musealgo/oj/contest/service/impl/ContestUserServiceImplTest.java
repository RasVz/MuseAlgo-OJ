package com.musealgo.oj.contest.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import com.musealgo.oj.api.user.UserQuery;
import com.musealgo.oj.contest.entity.Contest;
import com.musealgo.oj.contest.entity.ContestUser;
import com.musealgo.oj.contest.mapper.ContestMapper;
import com.musealgo.oj.contest.mapper.ContestUserMapper;
import com.musealgo.oj.contest.service.ContestEligibilityService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/** T10-1 报名：快照报名时 Rating、超限仍可报名但 eligible=0、审核状态。 */
@ExtendWith(MockitoExtension.class)
class ContestUserServiceImplTest {

    @Mock private ContestMapper contestMapper;
    @Mock private ContestUserMapper contestUserMapper;
    @Mock private UserQuery userQuery;
    @Mock private ContestEligibilityService eligibilityService;
    @InjectMocks private ContestUserServiceImpl service;

    private Contest contest(boolean review) {
        Contest c = new Contest();
        c.setVisible(1);
        c.setId(1L);
        c.setCategory("FORMAL");
        c.setRegistrationReview(review ? 1 : 0);
        c.setRatingMin(1000);
        c.setRatingMax(1800);
        c.setIsDeleted(0);
        return c;
    }

    @Test
    void registerSnapshotsRatingAndEligibilityForOverLimitUser() {
        ReflectionTestUtils.setField(service, "baseMapper", contestUserMapper);
        when(contestMapper.selectByIdForUpdate(1L)).thenReturn(contest(false));
        when(contestUserMapper.selectOne(any(), anyBoolean())).thenReturn(null);
        when(userQuery.findRating(7L)).thenReturn(1900);
        when(eligibilityService.isEligible(any(), any())).thenReturn(false);
        when(contestUserMapper.insert(any(ContestUser.class))).thenReturn(1);
        when(contestMapper.update(any(), any())).thenReturn(1);

        ContestUser record = service.registerContest(1L, 7L);

        assertEquals(1900, record.getRatingAtRegistration());
        assertEquals(0, record.getEligible());
        assertEquals(ContestUserServiceImpl.STATUS_AUTO_APPROVED, record.getStatus());
        verify(contestMapper).update(any(), any());
    }

    @Test
    void registrationReviewMakesRegistrationPending() {
        ReflectionTestUtils.setField(service, "baseMapper", contestUserMapper);
        when(contestMapper.selectByIdForUpdate(1L)).thenReturn(contest(true));
        when(contestUserMapper.selectOne(any(), anyBoolean())).thenReturn(null);
        when(userQuery.findRating(7L)).thenReturn(1500);
        when(eligibilityService.isEligible(any(), any())).thenReturn(true);
        when(contestUserMapper.insert(any(ContestUser.class))).thenReturn(1);
        when(contestMapper.update(any(), any())).thenReturn(1);

        ContestUser record = service.registerContest(1L, 7L);

        assertEquals(ContestUserServiceImpl.STATUS_PENDING, record.getStatus());
        assertEquals(1, record.getEligible());
    }

    @Test
    void duplicateRegistrationIsRejected() {
        ReflectionTestUtils.setField(service, "baseMapper", contestUserMapper);
        when(contestMapper.selectByIdForUpdate(1L)).thenReturn(contest(false));
        when(contestUserMapper.selectOne(any(), anyBoolean())).thenReturn(new ContestUser());
        assertThrows(com.musealgo.oj.common.exception.BusinessException.class, () -> service.registerContest(1L, 7L));
    }
}
