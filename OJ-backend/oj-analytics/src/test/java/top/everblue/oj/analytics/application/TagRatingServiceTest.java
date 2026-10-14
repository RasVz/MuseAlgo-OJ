package top.everblue.oj.analytics.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.everblue.oj.analytics.entity.ProblemTag;
import top.everblue.oj.analytics.entity.UserTagRating;
import top.everblue.oj.analytics.mapper.ProblemTagMapper;
import top.everblue.oj.analytics.mapper.TagMapper;
import top.everblue.oj.analytics.mapper.UserTagRatingMapper;
import top.everblue.oj.api.problem.ProblemDigest;
import top.everblue.oj.api.problem.ProblemQuery;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
