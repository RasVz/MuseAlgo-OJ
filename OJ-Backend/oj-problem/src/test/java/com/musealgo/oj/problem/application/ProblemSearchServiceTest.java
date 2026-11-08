package com.musealgo.oj.problem.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.vo.ProblemVO;
import com.musealgo.oj.problem.infrastructure.ProblemSearchIndexer;
import com.musealgo.oj.problem.infrastructure.ProblemSearchIndexer.SearchHits;
import com.musealgo.oj.problem.mapper.ProblemMapper;
import com.musealgo.oj.problem.service.ProblemService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 搜索读侧编排单测（任务⑧）：Meilisearch 命中回查 / 异常降级 LIKE / 空关键词直通 / 未登录 userStatus。
 */
class ProblemSearchServiceTest {

    private ProblemSearchIndexer searchIndexer;
    private ProblemService problemService;
    private ProblemMapper problemMapper;
    private ProblemSearchService service;

    @BeforeEach
    void setUp() {
        searchIndexer = mock(ProblemSearchIndexer.class);
        problemService = mock(ProblemService.class);
        problemMapper = mock(ProblemMapper.class);
        service = new ProblemSearchService(searchIndexer, problemService, problemMapper);
    }

    private Problem problem(long id, String title) {
        Problem p = new Problem();
        p.setId(id);
        p.setTitle(title);
        p.setIsPublic(true);
        return p;
    }

    @Test
    void searchReturnsHitsOrderedByMeilisearchRelevance() {
        Problem p2 = problem(2L, "二分查找");
        Problem p1 = problem(1L, "二分答案");
        when(searchIndexer.searchIds(eq("二分"), anyInt(), anyInt(), isNull(), isNull(), isNull()))
                .thenReturn(new SearchHits(List.of(2L, 1L), 2L));
        when(problemMapper.selectVisibleSearchIds(List.of(2L, 1L), null, null, null)).thenReturn(List.of(1L, 2L));
        when(problemService.listByIds(List.of(2L, 1L))).thenReturn(List.of(p2, p1));
        when(problemMapper.selectUserStatusByProblemIds(100L, List.of(2L, 1L)))
                .thenReturn(List.of(Map.of("problemId", 2L, "userStatus", 1)));

        Page<ProblemVO> result = service.search("二分", 1, 20, null, null, null, 100L);

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getRecords()).extracting(ProblemVO::getId).containsExactly(2L, 1L);
        assertThat(result.getRecords().get(0).getUserStatus()).isEqualTo(1);
        assertThat(result.getRecords().get(1).getUserStatus()).isZero();
    }

    @Test
    void meilisearchFailureFallsBackToLikeQuery() {
        when(searchIndexer.searchIds(eq("二分"), anyInt(), anyInt(), isNull(), isNull(), isNull()))
                .thenThrow(new IllegalStateException("meilisearch query failed"));
        Page<ProblemVO> likePage = new Page<>(1, 20, 2);
        likePage.setRecords(List.of(new ProblemVO()));
        when(problemService.getProblemVOPage(eq(1), eq(20), eq("二分"), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), eq(100L))).thenReturn(likePage);

        Page<ProblemVO> result = service.search("二分", 1, 20, null, null, null, 100L);

        assertThat(result.getTotal()).isEqualTo(2);
        verify(problemService).getProblemVOPage(anyInt(), anyInt(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyLong());
        verifyNoInteractions(problemMapper);
    }

    @Test
    void emptyKeywordGoesDirectlyToLikeQuery() {
        Page<ProblemVO> likePage = new Page<>(1, 20, 0);
        likePage.setRecords(List.of());
        when(problemService.getProblemVOPage(anyInt(), anyInt(), eq(""), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(likePage);

        service.search("", 1, 20, null, null, null, null);

        verify(searchIndexer, never()).searchIds(any(), anyInt(), anyInt(), any(), any(), any());
        verify(problemService).getProblemVOPage(anyInt(), anyInt(), eq(""), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void difficultyRangeIsPassedToMeilisearchAndNoStatusQueryWhenAnonymous() {
        Problem p = problem(3L, "数字三角形");
        when(searchIndexer.searchIds(eq("三角形"), anyInt(), anyInt(), isNull(), eq(800), eq(1200)))
                .thenReturn(new SearchHits(List.of(3L), 1L));
        when(problemMapper.selectVisibleSearchIds(List.of(3L), null, 800, 1200)).thenReturn(List.of(3L));
        when(problemService.listByIds(List.of(3L))).thenReturn(List.of(p));

        Page<ProblemVO> result = service.search("三角形", 1, 10, null, 800, 1200, null);

        assertThat(result.getRecords()).hasSize(1);
        verify(problemMapper, never()).selectUserStatusByProblemIds(any(), any());
        assertThat(result.getRecords().get(0).getUserStatus()).isZero();
    }

    @Test
    void nonPublicProblemsFromIndexAreFilteredOutByDbLookup() {
        Problem hidden = problem(4L, "私有题");
        hidden.setIsPublic(false);
        when(searchIndexer.searchIds(eq("私有"), anyInt(), anyInt(), isNull(), isNull(), isNull()))
                .thenReturn(new SearchHits(List.of(4L), 1L));
        when(problemMapper.selectVisibleSearchIds(List.of(4L), null, null, null)).thenReturn(List.of());

        Page<ProblemVO> result = service.search("私有", 1, 20, null, null, null, null);

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isZero();
    }

    @Test
    void emptyPageBeyondLastStillPreservesSearchTotal() {
        List<Long> ids = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        when(searchIndexer.searchIds(eq("sum"), anyInt(), anyInt(), isNull(), isNull(), isNull()))
                .thenReturn(new SearchHits(ids, 7L));
        when(problemMapper.selectVisibleSearchIds(ids, null, null, null)).thenReturn(ids);
        Page<ProblemVO> result = service.search("sum", 99, 20, null, null, null, null);
        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isEqualTo(7);
    }

    @Test
    void hiddenDeletedAndMissingHitsOnOtherPagesDoNotCountOrLeavePageHoles() {
        List<Long> candidates = List.of(9L, 2L, 99L, 4L, 8L);
        when(searchIndexer.searchIds(eq("stale"), anyInt(), anyInt(), isNull(), isNull(), isNull()))
                .thenReturn(new SearchHits(candidates, 5));
        when(problemMapper.selectVisibleSearchIds(candidates, null, null, null)).thenReturn(List.of(4L, 2L));
        when(problemService.listByIds(List.of(2L))).thenReturn(List.of(problem(2L, "First")));
        when(problemService.listByIds(List.of(4L))).thenReturn(List.of(problem(4L, "Second")));

        Page<ProblemVO> first = service.search("stale", 1, 1, null, null, null, null);
        Page<ProblemVO> second = service.search("stale", 2, 1, null, null, null, null);
        Page<ProblemVO> beyond = service.search("stale", 3, 1, null, null, null, null);
        assertThat(first.getTotal()).isEqualTo(2);
        assertThat(second.getTotal()).isEqualTo(2);
        assertThat(beyond.getTotal()).isEqualTo(2);
        assertThat(first.getRecords()).extracting(ProblemVO::getId).containsExactly(2L);
        assertThat(second.getRecords()).extracting(ProblemVO::getId).containsExactly(4L);
        assertThat(beyond.getRecords()).isEmpty();
    }

    @Test
    void currentDatabaseDifficultyCanExcludeAnOutdatedIndexHit() {
        when(searchIndexer.searchIds(eq("stale"), anyInt(), anyInt(), eq(800), eq(700), eq(900)))
                .thenReturn(new SearchHits(List.of(2L), 1));
        when(problemMapper.selectVisibleSearchIds(List.of(2L), 800, 700, 900)).thenReturn(List.of());
        Page<ProblemVO> result = service.search("stale", 1, 10, 800, 700, 900, null);
        assertThat(result.getTotal()).isZero();
        assertThat(result.getRecords()).isEmpty();
    }

    @Test
    void tooManyCandidatesFallBackWithoutClaimingATruncatedTotal() {
        when(searchIndexer.searchIds(eq("large"), anyInt(), anyInt(), isNull(), isNull(), isNull()))
                .thenReturn(new SearchHits(List.of(2L), 1001));
        Page<ProblemVO> databasePage = new Page<>(1, 20, 1200);
        when(problemService.getProblemVOPage(eq(1), eq(20), eq("large"), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(databasePage);
        assertThat(service.search("large", 1, 20, null, null, null, null)).isSameAs(databasePage);
        verifyNoInteractions(problemMapper);
    }

    @Test
    void incompleteCandidateWindowFallsBackInsteadOfReturningAnIncorrectCount() {
        when(searchIndexer.searchIds(eq("partial"), anyInt(), anyInt(), isNull(), isNull(), isNull()))
                .thenReturn(new SearchHits(List.of(2L), 2));
        Page<ProblemVO> databasePage = new Page<>(1, 20, 2);
        when(problemService.getProblemVOPage(eq(1), eq(20), eq("partial"), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(databasePage);
        assertThat(service.search("partial", 1, 20, null, null, null, null)).isSameAs(databasePage);
    }

    @Test
    void serverCappedTotalDoesNotMasqueradeAsACompleteCandidateWindow() {
        when(searchIndexer.searchIds(eq("capped"), anyInt(), anyInt(), isNull(), isNull(), isNull()))
                .thenReturn(new SearchHits(List.of(2L), 1, false));
        Page<ProblemVO> databasePage = new Page<>(1, 20, 2);
        when(problemService.getProblemVOPage(eq(1), eq(20), eq("capped"), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(databasePage);
        assertThat(service.search("capped", 1, 20, null, null, null, null)).isSameAs(databasePage);
    }
}
