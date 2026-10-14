package top.everblue.oj.problem.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.SearchResult;
import com.meilisearch.sdk.model.TaskInfo;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import top.everblue.oj.problem.entity.Problem;
import top.everblue.oj.problem.service.ProblemService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 搜索索引组件单测（任务⑧）：索引成功 / 索引失败不抛 / 熔断 / 禁用 / 全量重建分批提交。
 */
class ProblemSearchIndexerTest {

    private ProblemService problemService;
    private JdbcTemplate jdbcTemplate;
    private Client client;
    private Index index;

    private ProblemSearchIndexer newIndexer(boolean enabled, int failureThreshold, long cooldownMs) {
        ProblemSearchIndexer indexer = new ProblemSearchIndexer(
                problemService, jdbcTemplate, new ObjectMapper(),
                enabled, "http://localhost:7700", "test-key",
                failureThreshold, cooldownMs);
        indexer.setClientForTesting(client);
        return indexer;
    }

    @BeforeEach
    void setUp() {
        problemService = mock(ProblemService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        client = mock(Client.class);
        index = mock(Index.class);
        when(client.index(ProblemSearchIndexer.INDEX_UID)).thenReturn(index);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(index.updateDocuments(anyString())).thenReturn(mock(TaskInfo.class));
        when(index.addDocuments(anyString())).thenReturn(mock(TaskInfo.class));
        when(index.deleteDocument(anyString())).thenReturn(mock(TaskInfo.class));
    }

    private Problem problem(long id) {
        Problem p = new Problem();
        p.setId(id);
        p.setTitle("求和问题 " + id);
        p.setTags("[\"DP\",\"Array\"]");
        p.setDifficulty(1200);
        p.setSource("GESP");
        p.setIsPublic(true);
        return p;
    }

    @Test
    void indexProblemSendsUpsertDocument() {
        ProblemSearchIndexer indexer = newIndexer(true, 3, 30000);
        indexer.indexProblem(problem(42));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(index).updateDocuments(captor.capture());
        assertThat(captor.getValue())
                .contains("\"id\":42", "\"title\":\"求和问题 42\"", "\"difficulty\":1200",
                        "\"isPublic\":true", "\"DP\"", "\"Array\"");
        assertThat(indexer.failureCount()).isZero();
    }

    @Test
    void indexFailureIsSwallowedWithWarningCount() {
        when(index.updateDocuments(anyString()))
                .thenThrow(new MeilisearchException("connection refused"));
        ProblemSearchIndexer indexer = newIndexer(true, 3, 30000);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> indexer.indexProblem(problem(1)));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> indexer.indexProblem(problem(2)));

        assertThat(indexer.failureCount()).isEqualTo(2);
    }

    @Test
    void deleteProblemSendsDeleteDocument() {
        ProblemSearchIndexer indexer = newIndexer(true, 3, 30000);
        indexer.deleteProblem(7L);

        verify(index).deleteDocument("7");
        assertThat(indexer.failureCount()).isZero();
    }

    @Test
    void circuitOpensAfterConsecutiveFailuresAndSearchThrows() {
        ProblemSearchIndexer indexer = newIndexer(true, 2, 60000);
        when(index.search(any(SearchRequest.class)))
                .thenThrow(new MeilisearchException("timeout"));

        assertThatThrownBy(() -> indexer.searchIds("abc", 1, 20, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("query failed");
        // 第二次失败即达阈值 → 熔断打开
        assertThatThrownBy(() -> indexer.searchIds("abc", 1, 20, null, null, null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(indexer.circuitOpen()).isTrue();
        assertThat(indexer.failureCount()).isEqualTo(2);
        // 熔断期内不再发起真实查询
        assertThatThrownBy(() -> indexer.searchIds("abc", 1, 20, null, null, null))
                .isInstanceOf(IllegalStateException.class);
        verify(index, times(2)).search(any(SearchRequest.class));
    }

    @Test
    void successfulSearchResetsFailureCount() {
        ProblemSearchIndexer indexer = newIndexer(true, 3, 60000);
        when(index.search(any(SearchRequest.class)))
                .thenThrow(new MeilisearchException("boom"))
                .thenReturn(searchResultWithIds(9L, 10L));

        assertThatThrownBy(() -> indexer.searchIds("a", 1, 20, null, null, null))   // 第 1 次失败
                .isInstanceOf(IllegalStateException.class);
        ProblemSearchIndexer.SearchHits hits = indexer.searchIds("a", 1, 20, null, null, null);  // 成功

        assertThat(hits.ids()).containsExactly(9L, 10L);
        assertThat(indexer.failureCount()).isZero();
    }

    @Test
    void disabledIndexerPerformsNoCallsAndSearchThrows() {
        ProblemSearchIndexer indexer = newIndexer(false, 3, 30000);

        indexer.indexProblem(problem(1));
        indexer.deleteProblem(1L);
        assertThatThrownBy(() -> indexer.searchIds("abc", 1, 20, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");

        verifyNoInteractions(client);
        verifyNoInteractions(index);
    }

    @Test
    void reindexAllSubmitsInBatchesUntilEmpty() {
        ProblemSearchIndexer indexer = newIndexer(true, 3, 30000);
        // 250 道题：第一页 200 条，第二页 50 条
        Page<Problem> pageOne = new Page<>(1, 200, 250);
        pageOne.setRecords(batchProblems(1, 200));
        Page<Problem> pageTwo = new Page<>(2, 200, 250);
        pageTwo.setRecords(batchProblems(201, 250));
        when(problemService.page(any(Page.class), any())).thenReturn(pageOne, pageTwo, new Page<>());

        ProblemSearchIndexer.ReindexResult result = indexer.reindexAll();

        assertThat(result.indexed()).isEqualTo(250);
        assertThat(result.total()).isEqualTo(250);
        verify(index).deleteAllDocuments();
        verify(index, times(2)).addDocuments(anyString());
        assertThat(indexer.failureCount()).isZero();
    }

    private List<Problem> batchProblems(int from, int to) {
        List<Problem> batch = new ArrayList<>();
        for (long id = from; id <= to; id++) {
            batch.add(problem(id));
        }
        return batch;
    }

    private SearchResult searchResultWithIds(Long... ids) {
        SearchResult result = mock(SearchResult.class);
        ArrayList<HashMap<String, Object>> hits = new ArrayList<>();
        for (Long id : ids) {
            HashMap<String, Object> hit = new HashMap<>();
            hit.put("id", id);
            hits.add(hit);
        }
        when(result.getHits()).thenReturn(hits);
        when(result.getEstimatedTotalHits()).thenReturn(ids.length);
        return result;
    }
}
