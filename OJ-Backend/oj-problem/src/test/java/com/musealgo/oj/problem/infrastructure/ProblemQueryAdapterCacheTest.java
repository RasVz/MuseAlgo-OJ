package com.musealgo.oj.problem.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.musealgo.oj.api.problem.TestCaseView;
import com.musealgo.oj.problem.entity.ProblemCase;
import com.musealgo.oj.problem.mapper.ProblemCaseMapper;
import com.musealgo.oj.problem.mapper.ProblemMapper;
import com.musealgo.oj.problem.service.TestCaseStorageService;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 判题用例缓存的真实行为：重复读取命中缓存，显式失效后立刻读到新用例。
 * <p>
 * 这是「教师改完用例后，下一次提交按新数据判题」的核心机制验证；
 * 缓存实现用真实 Caffeine + 真实文件读取，只有 Mapper 是 mock。
 * 事务提交后的失效时机由 {@code ProblemCaseApplicationServiceTest} 侧验证。
 * </p>
 */
class ProblemQueryAdapterCacheTest {

    private static final Long PROBLEM_ID = 11L;

    @TempDir
    Path tempDir;

    private ProblemCaseMapper problemCaseMapper;
    private TestCaseStorageService storage;
    private ProblemQueryAdapter adapter;

    @BeforeEach
    void setUp() {
        problemCaseMapper = mock(ProblemCaseMapper.class);
        storage = new TestCaseStorageService(tempDir.toString());
        adapter = new ProblemQueryAdapter(mock(ProblemMapper.class), problemCaseMapper, storage, 60, 100);
    }

    private ProblemCase caseWith(long id, int order, String input, String output) {
        ProblemCase one = new ProblemCase();
        one.setId(id);
        one.setProblemId(PROBLEM_ID);
        one.setCaseOrder(order);
        one.setScore(100);
        one.setStatus((byte) 1);
        one.setInputFilePath(storage.writeInput(PROBLEM_ID, id, input));
        one.setOutputFilePath(storage.writeOutput(PROBLEM_ID, id, output));
        return one;
    }

    @Test
    void cachedCasesAreReusedUntilExplicitlyEvicted() {
        when(problemCaseMapper.selectList(any())).thenReturn(List.of(caseWith(1L, 1, "old", "old")));

        List<TestCaseView> first = adapter.findTestCases(PROBLEM_ID);
        List<TestCaseView> second = adapter.findTestCases(PROBLEM_ID);

        assertThat(first).hasSize(1);
        assertThat(second).isSameAs(first); // 命中缓存，返回同一实例
        verify(problemCaseMapper, times(1)).selectList(any());

        // 模拟「用例被改写」：DB 与文件都换成新内容
        when(problemCaseMapper.selectList(any())).thenReturn(List.of(caseWith(2L, 1, "new", "new")));
        assertThat(adapter.findTestCases(PROBLEM_ID).get(0).input()).isEqualTo("old");

        adapter.evictProblemCases(PROBLEM_ID);
        List<TestCaseView> refreshed = adapter.findTestCases(PROBLEM_ID);
        assertThat(refreshed.get(0).input()).isEqualTo("new");
        verify(problemCaseMapper, times(2)).selectList(any());
    }

    @Test
    void nullProblemIdReturnsEmptyWithoutQuery() {
        assertThat(adapter.findTestCases(null)).isEmpty();
        adapter.evictProblemCases(null);
        verify(problemCaseMapper, times(0)).selectList(any());
    }
}
