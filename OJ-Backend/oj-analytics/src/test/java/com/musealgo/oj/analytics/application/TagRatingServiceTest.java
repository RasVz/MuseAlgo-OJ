package com.musealgo.oj.analytics.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.musealgo.oj.analytics.entity.ProblemTag;
import com.musealgo.oj.analytics.entity.UserTagRating;
import com.musealgo.oj.analytics.mapper.ProblemTagMapper;
import com.musealgo.oj.analytics.mapper.TagMapper;
import com.musealgo.oj.analytics.mapper.UserTagRatingMapper;
import com.musealgo.oj.api.problem.ProblemDigest;
import com.musealgo.oj.api.problem.ProblemQuery;
import com.musealgo.oj.api.submission.SubmissionQuery;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 标签 ELO 评分（seam：TagRatingService.updateRating 公开入口 + ELO 公式）。
 * 期望值均为独立手算：gain = round(50 × (1 − 1/(1+10^((D−R)/400))))。
 */
@ExtendWith(MockitoExtension.class)
class TagRatingServiceTest {

    @Mock
    private UserTagRatingMapper userTagRatingMapper;
    @Mock
    private ProblemTagMapper problemTagMapper;
    @Mock
    private TagMapper tagMapper;
    @Mock
    private ProblemQuery problemQuery;
    @Mock
    private SubmissionQuery submissionQuery;

    @InjectMocks
    private TagRatingService service;

    @Test
    void equalDifficultyYieldsHalfK() {
        // D=R → 期望 0.5 → gain = 25
        assertEquals(25, service.calcGain(800, 800));
    }

    @Test
    void lowerRatedStudentGainsMoreThanExpected() {
        // R=1200, D=800 → 期望≈0.909 → gain = round(50×0.0909) = 5
        assertEquals(5, service.calcGain(1200, 800));
    }

    @Test
    void difficultyBelowMinimumResolvesToDefault() {
        assertEquals(800, TagRatingService.resolveDifficulty(null));
        assertEquals(800, TagRatingService.resolveDifficulty(700));
        assertEquals(1500, TagRatingService.resolveDifficulty(1500));
    }

    @Test
    void updateRatingCapsAtProblemDifficultyPlusMargin() {
        Long userId = 1L;
        Long problemId = 10L;
        Long tagId = 100L;

        when(problemQuery.findDigestBatch(List.of(problemId)))
                .thenReturn(List.of(new ProblemDigest(problemId, "A+B", 800, true)));
        when(problemTagMapper.selectList(any()))
                .thenReturn(List.of(new ProblemTag(problemId, tagId, null)));

        UserTagRating existing = new UserTagRating();
        existing.setRating(999); // 已接近上限 800+200=1000
        when(userTagRatingMapper.selectByUserAndTags(userId, List.of(tagId)))
                .thenReturn(List.of(existing));

        service.updateRating(userId, problemId);

        // gain(999, 800) = round(50×(1−1/(1+10^(-199/400)))) ≈ 35 → 999+35=1034 → 截断到 1000
        verify(userTagRatingMapper).upsert(eq(userId), eq(tagId), eq(1000));
    }

    // ==================== T8 受控重判后的 ELO 重建 ====================

    /**
     * 重建必须「先整用户删除、再严格按 AC 提交的历史顺序重放」。
     * 用 InOrder 锁定顺序：如果先 update 后 delete，最终评分会被清空；
     * 如果不按 create_time 顺序重放，rating 只升不降的累计结果会与旧链路不一致。
     */
    @Test
    void rebuildForUserDeletesThenReplaysAcceptedSubmissionsInOrder() {
        Long userId = 1L;
        Long firstProblem = 10L;
        Long secondProblem = 20L;
        Long firstTag = 100L;
        Long secondTag = 200L;

        when(submissionQuery.findAcceptedChronological(userId)).thenReturn(List.of(
                new SubmissionQuery.AcceptedSubmission(
                        101L, firstProblem, LocalDateTime.of(2026, 10, 1, 10, 0)),
                new SubmissionQuery.AcceptedSubmission(
                        102L, secondProblem, LocalDateTime.of(2026, 10, 2, 11, 0))));

        // 第一题：难度 800 → gain 25，800+25=825；第二题：难度 1200 → gain 45，800+45=845
        when(problemQuery.findDigestBatch(List.of(firstProblem)))
                .thenReturn(List.of(new ProblemDigest(firstProblem, "A+B", 800, true)));
        when(problemQuery.findDigestBatch(List.of(secondProblem)))
                .thenReturn(List.of(new ProblemDigest(secondProblem, "C", 1200, true)));
        when(problemTagMapper.selectList(any()))
                .thenReturn(List.of(new ProblemTag(firstProblem, firstTag, null)))
                .thenReturn(List.of(new ProblemTag(secondProblem, secondTag, null)));
        when(userTagRatingMapper.selectByUserAndTags(userId, List.of(firstTag))).thenReturn(List.of());
        when(userTagRatingMapper.selectByUserAndTags(userId, List.of(secondTag))).thenReturn(List.of());

        service.rebuildForUser(userId);

        verify(submissionQuery).findAcceptedChronological(userId);
        InOrder order = inOrder(userTagRatingMapper);
        order.verify(userTagRatingMapper).deleteByUser(userId);
        order.verify(userTagRatingMapper).upsert(userId, firstTag, 825);
        order.verify(userTagRatingMapper).upsert(userId, secondTag, 845);
    }

    /** 没有任何 AC 提交时也要清空旧的派生评分（重判可能把全部 AC 改判为非 AC）。 */
    @Test
    void rebuildForUserWithoutAcceptedSubmissionsClearsRatingsOnly() {
        Long userId = 2L;
        when(submissionQuery.findAcceptedChronological(userId)).thenReturn(List.of());

        service.rebuildForUser(userId);

        verify(userTagRatingMapper).deleteByUser(userId);
        verify(userTagRatingMapper, never()).upsert(any(), any(), anyInt());
        verifyNoInteractions(problemQuery);
    }

    /** null 用户直接返回，不产生任何查询/写操作。 */
    @Test
    void rebuildForUserWithNullUserIdIsNoOp() {
        service.rebuildForUser(null);

        verifyNoInteractions(submissionQuery);
        verifyNoInteractions(userTagRatingMapper);
    }

    @Test
    void missingProblemOrTagsIsNoOp() {
        Long userId = 1L;
        Long problemId = 10L;

        when(problemQuery.findDigestBatch(List.of(problemId))).thenReturn(List.of());
        service.updateRating(userId, problemId);
        verify(problemTagMapper, never()).selectList(any());
        verify(userTagRatingMapper, never()).upsert(any(), any(), anyInt());
    }
}
