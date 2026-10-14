package top.everblue.oj.problem.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.everblue.oj.problem.entity.Problem;
import top.everblue.oj.problem.entity.vo.ProblemVO;
import top.everblue.oj.problem.infrastructure.ProblemSearchIndexer;
import top.everblue.oj.problem.infrastructure.ProblemSearchIndexer.SearchHits;
import top.everblue.oj.problem.mapper.ProblemMapper;
import top.everblue.oj.problem.service.ProblemService;

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
        when(searchIndexer.searchIds("二分", 1, 20, null, null, null))
                .thenReturn(new SearchHits(List.of(2L, 1L), 5L));
        when(problemService.listByIds(List.of(2L, 1L))).thenReturn(List.of(p2, p1));
        when(problemMapper.selectUserStatusByProblemIds(100L, List.of(2L, 1L)))
                .thenReturn(List.of(Map.of("problemId", 2L, "userStatus", 1)));

        Page<ProblemVO> result = service.search("二分", 1, 20, null, null, null, 100L);

        assertThat(result.getTotal()).isEqualTo(5);
        assertThat(result.getRecords()).extracting(ProblemVO::getId).containsExactly(2L, 1L);
        assertThat(result.getRecords().get(0).getUserStatus()).isEqualTo(1);
        assertThat(result.getRecords().get(1).getUserStatus()).isZero();
    }

    @Test
    void meilisearchFailureFallsBackToLikeQuery() {
        when(searchIndexer.searchIds("二分", 1, 20, null, null, null))
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
        when(searchIndexer.searchIds("三角形", 1, 10, null, 800, 1200))
                .thenReturn(new SearchHits(List.of(3L), 1L));
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
        when(searchIndexer.searchIds("私有", 1, 20, null, null, null))
                .thenReturn(new SearchHits(List.of(4L), 1L));
        when(problemService.listByIds(List.of(4L))).thenReturn(List.of(hidden));

        Page<ProblemVO> result = service.search("私有", 1, 20, null, null, null, null);

        assertThat(result.getRecords()).isEmpty();
    }
}
