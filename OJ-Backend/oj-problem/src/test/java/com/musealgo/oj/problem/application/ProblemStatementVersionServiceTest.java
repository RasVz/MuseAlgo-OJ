package com.musealgo.oj.problem.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.ProblemStatementVersion;
import com.musealgo.oj.problem.format.FormatSample;
import com.musealgo.oj.problem.format.ProblemFormatApplyResult;
import com.musealgo.oj.problem.format.ProblemFormattedContent;
import com.musealgo.oj.problem.format.ProblemStatementSnapshot;
import com.musealgo.oj.problem.format.StatementVersionApplyRequest;
import com.musealgo.oj.problem.mapper.ProblemStatementVersionMapper;
import com.musealgo.oj.problem.service.ProblemService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 候选版本的审阅边界：只有 CANDIDATE 能应用，且只把选中字段交给写入层。 */
class ProblemStatementVersionServiceTest {

    private static final long PROBLEM_ID = 21L;
    private static final long VERSION_ID = 305L;

    private ProblemStatementVersionMapper versionMapper;
    private ProblemFormatApplicationService formatService;
    private ProblemStatementVersionService service;
    private Problem problem;

    @BeforeAll
    static void initTableInfo() {
        // LambdaUpdateWrapper 依赖 MyBatis-Plus 的 TableInfo 缓存；纯单测没有 MyBatis 上下文，需要手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ProblemStatementVersion.class);
    }

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "teacher", null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));

        problem = new Problem();
        problem.setId(PROBLEM_ID);
        problem.setTitle("旧题名");
        problem.setContent("旧正文");

        versionMapper = mock(ProblemStatementVersionMapper.class);
        formatService = mock(ProblemFormatApplicationService.class);
        when(formatService.snapshot(any(Problem.class))).thenReturn(snapshot("旧题名", "旧正文"));

        ProblemAccessGuard accessGuard = mock(ProblemAccessGuard.class);
        when(accessGuard.requireManagedProblem(PROBLEM_ID)).thenReturn(problem);
        ProblemService problemService = mock(ProblemService.class);
        when(problemService.getById(PROBLEM_ID)).thenReturn(problem);

        service = new ProblemStatementVersionService(versionMapper, formatService, accessGuard, problemService,
                new ObjectMapper(), new NoopTransactionManager());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void onlyCandidateVersionsCanBeApplied() {
        when(versionMapper.selectById(VERSION_ID)).thenReturn(version(ProblemStatementVersionService.STATUS_LIVE));

        BusinessException failure = assertThrows(BusinessException.class, () -> service.applyCandidate(
                PROBLEM_ID, VERSION_ID, new StatementVersionApplyRequest(List.of("title"), content("新题名"))));

        assertEquals("只有待审候选版本可以应用", failure.getMessage());
        verify(formatService, never()).applyFieldsForVersion(any(), any(), any());
    }

    @Test
    void applyPassesOnlySelectedFieldsToTheWriter() {
        when(versionMapper.selectById(VERSION_ID)).thenReturn(version(ProblemStatementVersionService.STATUS_CANDIDATE));
        when(formatService.applyFieldsForVersion(eq(PROBLEM_ID), any(), any()))
                .thenReturn(new ProblemFormatApplyResult(PROBLEM_ID, List.of("title"), "hash"));

        service.applyCandidate(PROBLEM_ID, VERSION_ID,
                new StatementVersionApplyRequest(List.of("title"), content("新题名")));

        verify(formatService).applyFieldsForVersion(eq(PROBLEM_ID), any(), eq(List.of("title")));
        // 一次归档旧的 LIVE，一次把候选提升为 LIVE
        verify(versionMapper, times(2)).update(isNull(), any());
    }

    @Test
    void blockingIssueOnSelectedFieldStopsTheWrite() {
        when(versionMapper.selectById(VERSION_ID)).thenReturn(version(ProblemStatementVersionService.STATUS_CANDIDATE));

        BusinessException failure = assertThrows(BusinessException.class, () -> service.applyCandidate(
                PROBLEM_ID, VERSION_ID, new StatementVersionApplyRequest(List.of("content"),
                        new ProblemFormattedContent("新题名", "   ", "", "", "", 1000, 512, List.of()))));

        assertEquals("存在阻断问题，无法应用：题目正文为空，无法保存", failure.getMessage());
        verify(formatService, never()).applyFieldsForVersion(any(), any(), any());
    }

    private ProblemStatementVersion version(String status) {
        ProblemStatementVersion version = new ProblemStatementVersion();
        version.setId(VERSION_ID);
        version.setProblemId(PROBLEM_ID);
        version.setVersionNo(2);
        version.setStatus(status);
        version.setSource(ProblemStatementVersionService.SOURCE_AI);
        version.setTitle("新题名");
        version.setContent("整理后的正文，长度足够避免触发过短规则。".repeat(4));
        version.setTimeLimit(1000);
        version.setMemoryLimit(512);
        version.setSamplesJson("[]");
        version.setIssuesJson("[]");
        return version;
    }

    private ProblemStatementSnapshot snapshot(String title, String content) {
        return new ProblemStatementSnapshot(title, content, "", "", "", 1000, 512, List.of());
    }

    private ProblemFormattedContent content(String title) {
        return new ProblemFormattedContent(title, "整理后的正文，长度足够避免触发过短规则。".repeat(4),
                "", "", "", 1000, 512, List.of(new FormatSample("1", "1")));
    }

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
