package com.musealgo.oj.problem.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musealgo.oj.api.ai.AiAssistant;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.ProblemSample;
import com.musealgo.oj.problem.error.ProblemErrorCode;
import com.musealgo.oj.problem.format.FormatSample;
import com.musealgo.oj.problem.format.ProblemFormatApplyRequest;
import com.musealgo.oj.problem.format.ProblemFormatApplyResult;
import com.musealgo.oj.problem.format.ProblemFormattedContent;
import com.musealgo.oj.problem.format.ProblemStatementHasher;
import com.musealgo.oj.problem.infrastructure.ProblemSearchIndexer;
import com.musealgo.oj.problem.service.ProblemService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 应用整理结果的关键分支：哈希基线、字段白名单、样例保护、服务端二次质量闸门。
 * <p>事务用无操作实现替代真实数据源，断言全部落在实体与 mock 的样例服务上。</p>
 */
class ProblemFormatApplyTest {

    private static final long PROBLEM_ID = 11L;

    private Problem problem;
    private ProblemService problemService;
    private ProblemSampleApplicationService sampleService;
    private ProblemFormatApplicationService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "teacher", null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));

        problem = new Problem();
        problem.setId(PROBLEM_ID);
        problem.setTitle("旧题名");
        problem.setContent("旧正文内容");
        problem.setInputDesc("旧输入");
        problem.setOutputDesc("旧输出");
        problem.setHint("旧提示");
        problem.setTimeLimit(1000);
        problem.setMemoryLimit(512);

        problemService = mock(ProblemService.class);
        when(problemService.getById(PROBLEM_ID)).thenReturn(problem);
        when(problemService.updateById(any(Problem.class))).thenReturn(true);

        ProblemAccessGuard accessGuard = mock(ProblemAccessGuard.class);
        when(accessGuard.requireManagedProblem(PROBLEM_ID)).thenReturn(problem);

        sampleService = mock(ProblemSampleApplicationService.class);
        when(sampleService.getSamples(PROBLEM_ID)).thenReturn(List.of());

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), any())).thenReturn(List.of(PROBLEM_ID));

        service = new ProblemFormatApplicationService(problemService, new ObjectMapper(), mock(AiAssistant.class),
                new NoopTransactionManager(), mock(ProblemSearchIndexer.class), accessGuard, sampleService, jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void applyWithoutBaseHashIsRejected() {
        BusinessException failure = assertThrows(BusinessException.class, () -> service.applyFormattedProblem(
                new ProblemFormatApplyRequest(PROBLEM_ID, null, List.of("title"), content("新题名"))));

        assertEquals(ProblemErrorCode.PROBLEM_INPUT_INVALID, failure.getErrorCode());
        assertEquals("旧题名", problem.getTitle());
    }

    @Test
    void staleBaseHashIsRejected() {
        BusinessException failure = assertThrows(BusinessException.class, () -> service.applyFormattedProblem(
                new ProblemFormatApplyRequest(PROBLEM_ID, "stale-hash", List.of("title"), content("新题名"))));

        assertEquals(ProblemErrorCode.PROBLEM_CONTENT_CHANGED, failure.getErrorCode());
        assertEquals("旧题名", problem.getTitle());
        verify(problemService, never()).updateById(any(Problem.class));
    }

    @Test
    void fieldWhitelistOnlyWritesSelectedFields() {
        ProblemFormatApplyResult result = service.applyFormattedProblem(
                new ProblemFormatApplyRequest(PROBLEM_ID, currentHash(), List.of("title"), content("新题名")));

        assertEquals(List.of("title"), result.appliedFields());
        assertEquals("新题名", problem.getTitle());
        assertEquals("旧正文内容", problem.getContent());
        assertEquals("旧提示", problem.getHint());
        verify(sampleService, never()).replaceFormatSamples(anyLong(), anyList());
    }

    @Test
    void emptyFieldsMeansAllFieldsForBackwardCompatibility() {
        ProblemFormatApplyResult result = service.applyFormattedProblem(
                new ProblemFormatApplyRequest(PROBLEM_ID, currentHash(), List.of(), content("新题名")));

        assertEquals(List.of("title", "content", "inputDesc", "outputDesc", "hint", "timeLimit", "memoryLimit", "samples"),
                result.appliedFields());
        assertEquals(2000, problem.getTimeLimit());
        assertEquals(256, problem.getMemoryLimit());
    }

    @Test
    void blankSamplePairNeverClearsExistingSamplesWhenOtherFieldsApply() {
        when(sampleService.getSamples(PROBLEM_ID)).thenReturn(List.of(sample("1", "1")));

        ProblemFormatApplyResult result = service.applyFormattedProblem(
                new ProblemFormatApplyRequest(PROBLEM_ID, currentHash(), List.of("title", "samples"),
                        contentWithBlankSample("新题名")));

        assertEquals(List.of("title"), result.appliedFields());
        verify(sampleService, never()).replaceFormatSamples(anyLong(), anyList());
        assertEquals("新题名", problem.getTitle());
    }

    @Test
    void lostSamplesAreBlockedBeforeTouchingTheDatabase() {
        when(sampleService.getSamples(PROBLEM_ID)).thenReturn(List.of(sample("1", "1")));

        BusinessException failure = assertThrows(BusinessException.class, () -> service.applyFormattedProblem(
                new ProblemFormatApplyRequest(PROBLEM_ID, currentHash(), List.of("samples"), contentNoSamples("新题名"))));

        assertEquals(ProblemErrorCode.PROBLEM_INPUT_INVALID, failure.getErrorCode());
        assertTrue(failure.getMessage().contains("样例"));
        verify(sampleService, never()).replaceFormatSamples(anyLong(), anyList());
    }

    @Test
    void lostSamplesBlockOnlyWhenSamplesAreSelected() {
        when(sampleService.getSamples(PROBLEM_ID)).thenReturn(List.of(sample("1", "1")));

        ProblemFormatApplyResult result = service.applyFormattedProblem(
                new ProblemFormatApplyRequest(PROBLEM_ID, currentHash(), List.of("title"), contentNoSamples("新题名")));

        assertEquals(List.of("title"), result.appliedFields());
        verify(sampleService, never()).replaceFormatSamples(anyLong(), anyList());
    }

    @Test
    void samplesAreReplacedWhenResultHasSamples() {
        ProblemFormatApplyResult result = service.applyFormattedProblem(
                new ProblemFormatApplyRequest(PROBLEM_ID, currentHash(), List.of("samples"),
                        new ProblemFormattedContent("新题名", "整理后的正文", "", "", "", 1000, 512,
                                List.of(new FormatSample("2", "4")))));

        assertEquals(List.of("samples"), result.appliedFields());
        verify(sampleService).replaceFormatSamples(PROBLEM_ID, List.of(new FormatSample("2", "4")));
    }

    @Test
    void blankTitleIsBlockedWhenTitleSelected() {
        BusinessException failure = assertThrows(BusinessException.class, () -> service.applyFormattedProblem(
                new ProblemFormatApplyRequest(PROBLEM_ID, currentHash(), List.of("title"),
                        new ProblemFormattedContent("  ", "整理后的正文", "", "", "", 1000, 512, List.of()))));

        assertEquals(ProblemErrorCode.PROBLEM_INPUT_INVALID, failure.getErrorCode());
        assertEquals("旧题名", problem.getTitle());
    }

    @Test
    void blankContentIsBlockedWhenContentSelected() {
        BusinessException failure = assertThrows(BusinessException.class, () -> service.applyFormattedProblem(
                new ProblemFormatApplyRequest(PROBLEM_ID, currentHash(), List.of("content"),
                        new ProblemFormattedContent("新题名", "   ", "", "", "", 1000, 512, List.of()))));

        assertEquals(ProblemErrorCode.PROBLEM_INPUT_INVALID, failure.getErrorCode());
        assertEquals("旧正文内容", problem.getContent());
    }

    @Test
    void unclosedCodeFenceBlocksContentButNotTitleOnlyApply() {
        String broken = "整理后的正文\n```\nint main() {}";
        BusinessException failure = assertThrows(BusinessException.class, () -> service.applyFormattedProblem(
                new ProblemFormatApplyRequest(PROBLEM_ID, currentHash(), List.of("content"),
                        new ProblemFormattedContent("新题名", broken, "", "", "", 1000, 512, List.of()))));
        assertEquals(ProblemErrorCode.PROBLEM_INPUT_INVALID, failure.getErrorCode());

        ProblemFormatApplyResult titleOnly = service.applyFormattedProblem(
                new ProblemFormatApplyRequest(PROBLEM_ID, currentHash(), List.of("title"),
                        new ProblemFormattedContent("新题名", broken, "", "", "", 1000, 512, List.of())));
        assertEquals(List.of("title"), titleOnly.appliedFields());
    }

    @Test
    void unknownFieldsAreIgnoredButNeverApplied() {
        ProblemFormatApplyResult result = service.applyFormattedProblem(
                new ProblemFormatApplyRequest(PROBLEM_ID, currentHash(), List.of("difficulty", "title"),
                        content("新题名")));

        assertEquals(List.of("title"), result.appliedFields());
    }

    @Test
    void applyReturnsFreshHashForNextComparison() {
        String before = currentHash();
        ProblemFormatApplyResult result = service.applyFormattedProblem(
                new ProblemFormatApplyRequest(PROBLEM_ID, before, List.of("title"), content("新题名")));

        assertEquals(64, result.baseHash().length());
        assertTrue(!before.equals(result.baseHash()));
        // 新哈希可直接用于第二轮应用，说明返回的是落库后的真实状态
        service.applyFormattedProblem(new ProblemFormatApplyRequest(
                PROBLEM_ID, result.baseHash(), List.of("title"), content("再改一次")));
        assertEquals("再改一次", problem.getTitle());
    }

    @Test
    void versionApplySkipsHashCheckButStillWritesOnlySelectedFields() {
        ProblemFormatApplyResult result = service.applyFieldsForVersion(
                PROBLEM_ID, content("版本题名"), List.of("title"));

        assertEquals(List.of("title"), result.appliedFields());
        assertEquals("版本题名", problem.getTitle());
        assertEquals("旧正文内容", problem.getContent());
        verify(sampleService, never()).replaceFormatSamples(anyLong(), anyList());
    }

    @Test
    void versionApplyStillBlocksOnQualityError() {
        BusinessException failure = assertThrows(BusinessException.class, () -> service.applyFieldsForVersion(
                PROBLEM_ID, new ProblemFormattedContent("新题名", "   ", "", "", "", 1000, 512, List.of()),
                List.of("content")));

        assertEquals(ProblemErrorCode.PROBLEM_INPUT_INVALID, failure.getErrorCode());
        assertEquals("旧正文内容", problem.getContent());
    }

    private String currentHash() {
        return ProblemStatementHasher.hash(service.snapshot(problem));
    }

    private static ProblemFormattedContent content(String title) {
        return new ProblemFormattedContent(title, "整理后的正文", "输入格式", "输出格式", "提示", 2000, 256,
                List.of(new FormatSample("1", "1")));
    }

    private static ProblemFormattedContent contentNoSamples(String title) {
        return new ProblemFormattedContent(title, "整理后的正文", "输入格式", "输出格式", "提示", 2000, 256,
                List.of());
    }

    private static ProblemFormattedContent contentWithBlankSample(String title) {
        return new ProblemFormattedContent(title, "整理后的正文", "输入格式", "输出格式", "提示", 2000, 256,
                List.of(new FormatSample("  ", "")));
    }

    private static ProblemSample sample(String input, String output) {
        ProblemSample sample = new ProblemSample();
        sample.setInput(input);
        sample.setOutput(output);
        return sample;
    }

    /** 只提供事务边界，不做真实持久化；业务断言全部落在 mock 的 mapper 与实体上。 */
    private static final class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // no-op
        }

        @Override
        public void rollback(TransactionStatus status) {
            // no-op
        }
    }
}
