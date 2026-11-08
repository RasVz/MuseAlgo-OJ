package com.musealgo.oj.analytics.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.musealgo.oj.analytics.domain.MasteryLevel;
import com.musealgo.oj.analytics.domain.StudentProfile;
import com.musealgo.oj.analytics.entity.Tag;
import com.musealgo.oj.analytics.mapper.ProfileSnapshotMapper;
import com.musealgo.oj.analytics.mapper.TagMapper;
import com.musealgo.oj.api.profile.ProfileQuery;
import com.musealgo.oj.api.profile.TagStat;
import com.musealgo.oj.api.teaching.ClassQuery;
import com.musealgo.oj.api.user.UserDisplayInfo;
import com.musealgo.oj.api.user.UserQuery;
import com.musealgo.oj.common.security.AuthenticatedUser;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 画像组装是周报的唯一数据来源，但此前没有单测。这里固定三件最容易被改坏的事：
 * 维度掌握度上卷、班级百分位口径（严格小于、排除自己、单人班级无分母）、
 * 以及展示名优先级（班级 remark &gt; 昵称 &gt; 用户名，查询失败降级）。
 */
class ProfileApplicationServiceTest {

    private static final Long STUDENT_ID = 11L;
    private static final Long CLASS_ID = 7L;
    private static final LocalDate FROM = LocalDate.of(2026, 10, 12);
    private static final LocalDate TO = LocalDate.of(2026, 10, 18);

    private final ReportAccessService accessService = mock(ReportAccessService.class);
    private final ProfileQuery profileQuery = mock(ProfileQuery.class);
    private final UserQuery userQuery = mock(UserQuery.class);
    private final ClassQuery classQuery = mock(ClassQuery.class);
    private final TagMapper tagMapper = mock(TagMapper.class);
    private final ProfileSnapshotMapper snapshotMapper = mock(ProfileSnapshotMapper.class);
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);

    private final ProfileApplicationService service = new ProfileApplicationService(
            accessService, new ObjectMapper(), profileQuery, userQuery, classQuery,
            tagMapper, snapshotMapper, redisTemplate);

    @Test
    void buildsDimensionsPercentilesAndWeakPointsFromClassSnapshot() {
        stubProfile();
        when(classQuery.findStudentIds(CLASS_ID)).thenReturn(List.of(11L, 12L, 13L));
        when(profileQuery.aggregateClassByDimension(anyCollection(), any(), any())).thenReturn(Map.of(
                12L, Map.of("ALGORITHM", new ProfileQuery.DimensionSummary(2, 0, 0, 2)),
                13L, Map.of("ALGORITHM", new ProfileQuery.DimensionSummary(2, 2, 1, 2))));
        when(userQuery.findDisplayInfo(STUDENT_ID)).thenReturn(Optional.of(
                new UserDisplayInfo(STUDENT_ID, "student11", "昵称", null, null, null, 0)));
        when(classQuery.findStudentRemark(CLASS_ID, STUDENT_ID)).thenReturn(Optional.of("张同学"));
        when(classQuery.findById(CLASS_ID)).thenReturn(Optional.of(
                new ClassQuery.ClassInfo(CLASS_ID, "信奥一班", 9L)));

        StudentProfile profile = service.buildStudentProfile(STUDENT_ID, CLASS_ID, FROM, TO);

        // 展示名：班级 remark 优先
        assertEquals("张同学", profile.studentName());
        assertEquals("信奥一班", profile.className());
        assertEquals(10, profile.weekOverview().totalSubmissions());

        // 维度上卷：本轮 2 题通过 1 题、一次通过 1 题 → 80% 通过率 + 20% 一次通过率 = 50
        StudentProfile.DimensionMastery algorithm = profile.dimensionMasteries().get("ALGORITHM");
        assertEquals(50, algorithm.mastery());
        assertEquals(MasteryLevel.WEAK, algorithm.level());
        assertEquals(2, algorithm.attempted());
        assertEquals(1, algorithm.passed());
        // 字典里存在但本周没有提交的维度也要出现在雷达图上（0 分）
        assertEquals(0, profile.dimensionMasteries().get("DP").mastery());

        // 百分位：班级 3 人，12 号 0 分低于 50，13 号 100 分不低 → 1/2 = 50
        assertEquals(50, profile.dimensionPercentiles().get("ALGORITHM"));
        assertEquals(0, profile.dimensionPercentiles().get("DP"));

        // 标签掌握度 45 < 55 → 薄弱点
        assertEquals(1, profile.weakPoints().size());
        assertEquals("贪心", profile.weakPoints().get(0).tagName());
        assertEquals(45, profile.weakPoints().get(0).mastery());
    }

    @Test
    void singleMemberClassHasNoPercentileDenominator() {
        stubProfile();
        when(classQuery.findStudentIds(CLASS_ID)).thenReturn(List.of(STUDENT_ID));
        when(profileQuery.aggregateClassByDimension(anyCollection(), any(), any())).thenReturn(Map.of());
        when(userQuery.findDisplayInfo(STUDENT_ID)).thenReturn(Optional.empty());
        when(classQuery.findStudentRemark(CLASS_ID, STUDENT_ID)).thenReturn(Optional.empty());
        when(classQuery.findById(CLASS_ID)).thenReturn(Optional.empty());

        StudentProfile profile = service.buildStudentProfile(STUDENT_ID, CLASS_ID, FROM, TO);

        assertTrue(profile.dimensionPercentiles().containsKey("ALGORITHM"));
        assertNull(profile.dimensionPercentiles().get("ALGORITHM"));
        // 找不到用户与班级时降级为固定文案，不抛异常
        assertEquals("未知学生", profile.studentName());
        assertNull(profile.className());
    }

    @Test
    void remarkLookupFailureFallsBackToNickname() {
        stubProfile();
        when(classQuery.findStudentIds(CLASS_ID)).thenReturn(List.of(STUDENT_ID));
        when(profileQuery.aggregateClassByDimension(anyCollection(), any(), any())).thenReturn(Map.of());
        when(userQuery.findDisplayInfo(STUDENT_ID)).thenReturn(Optional.of(
                new UserDisplayInfo(STUDENT_ID, "student11", "小杰", null, null, null, 0)));
        when(classQuery.findStudentRemark(CLASS_ID, STUDENT_ID))
                .thenThrow(new IllegalStateException("remark table unavailable"));
        when(classQuery.findById(CLASS_ID)).thenReturn(Optional.empty());

        StudentProfile profile = service.buildStudentProfile(STUDENT_ID, CLASS_ID, FROM, TO);

        assertEquals("小杰", profile.studentName());
    }

    @Test
    void legacyCacheEntryFromOldPackageIsDeletedAndRebuilt() {
        stubProfile();
        when(userQuery.findDisplayInfo(STUDENT_ID)).thenReturn(Optional.empty());
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString()))
                .thenThrow(new SerializationException("legacy profile payload uses a class name that no longer exists"));

        AuthenticatedUser user = mock(AuthenticatedUser.class);
        when(user.getId()).thenReturn(STUDENT_ID);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(user);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            StudentProfile profile = service.buildMyProfile(FROM, TO);

            assertEquals(STUDENT_ID, profile.studentId());
            String cacheKey = "profile:" + STUDENT_ID + ":" + FROM + ":" + TO;
            verify(redisTemplate).delete(cacheKey);
            verify(valueOps).set(eq(cacheKey), any(StudentProfile.class), eq(Duration.ofMinutes(5)));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void stubProfile() {
        when(profileQuery.weekOverview(any(), any(), any()))
                .thenReturn(new ProfileQuery.WeekOverview(10, 6, 4, 3));
        when(profileQuery.aggregateByTag(any(), any(), any())).thenReturn(List.of(
                new TagStat(3L, "贪心", 2L, "ALGORITHM", 2, 1, 1, 3, 120.0, 1200)));
        when(profileQuery.aggregateByDimension(any(), any(), any()))
                .thenReturn(Map.of("ALGORITHM", new ProfileQuery.DimensionSummary(2, 1, 1, 3)));
        when(tagMapper.selectDimensions()).thenReturn(List.of(
                dimension(1L, "ALGORITHM", "算法"),
                dimension(2L, "DP", "动态规划")));
    }

    private static Tag dimension(Long id, String code, String name) {
        Tag tag = new Tag();
        tag.setId(id);
        tag.setCode(code);
        tag.setName(name);
        tag.setDimension(code);
        return tag;
    }
    @Test void reversedProfileWindowIsRejectedBeforeStatisticsAreRead() {
        var failure = org.junit.jupiter.api.Assertions.assertThrows(com.musealgo.oj.common.exception.BusinessException.class,
                () -> service.buildStudentProfile(STUDENT_ID, CLASS_ID, TO, FROM));
        assertEquals(400, failure.getErrorCode().status());
        org.mockito.Mockito.verifyNoInteractions(profileQuery);
    }

}
