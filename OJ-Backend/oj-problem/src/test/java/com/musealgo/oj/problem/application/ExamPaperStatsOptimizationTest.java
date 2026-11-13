package com.musealgo.oj.problem.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.musealgo.oj.api.system.SystemConfigQuery;
import com.musealgo.oj.api.user.UserQuery;
import com.musealgo.oj.problem.entity.*;
import com.musealgo.oj.problem.mapper.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.BeforeAll;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExamPaperStatsOptimizationTest {
    @BeforeAll
    static void initializeMetadata() {
        for (Class<?> entity : List.of(ExamAnswer.class, ExamAttempt.class, ExamQuestion.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "stats-test"), entity);
        }
    }
    @Mock ExamAttemptMapper examAttemptMapper;
    @Mock ExamQuestionMapper examQuestionMapper;
    @Mock ExamAnswerMapper examAnswerMapper;
    @Mock ExamPaperMapper examPaperMapper;
    @Mock UserQuery userQuery;
    @Mock SystemConfigQuery systemConfigQuery;
    @InjectMocks ExamPaperStatsApplicationService service;

    @Test
    void statisticsUseLatestAttemptPerUserAndIncludeUnansweredInRate() {
        when(examAttemptMapper.selectList(any())).thenReturn(List.of(attempt(3L, 8L, 180), attempt(2L, 9L, 100), attempt(1L, 8L, 200)));
        ExamPaper paper = new ExamPaper(); paper.setTotalScore(200);
        when(examPaperMapper.selectById(10L)).thenReturn(paper);
        ExamQuestion q = new ExamQuestion(); q.setId(11L); q.setStatement("题目一");
        ExamQuestion q2 = new ExamQuestion(); q2.setId(12L); q2.setStatement("题目二");
        when(examQuestionMapper.selectList(any())).thenReturn(List.of(q, q2));
        ExamAnswer answer = new ExamAnswer(); answer.setQuestionId(11L); answer.setIsCorrect(1);
        when(examAnswerMapper.selectList(any())).thenReturn(List.of(answer));
        var stats = service.paperStats(10L);
        assertEquals(2, stats.getAttemptCount());
        assertEquals(140, stats.getAvgTotal());
        assertEquals(200, stats.getTotalScore());
        assertEquals(2, stats.getQuestionStats().get(0).getAttemptCount());
        assertEquals(50, stats.getQuestionStats().get(0).getCorrectRate());
        assertEquals(0, stats.getQuestionStats().get(1).getCorrectRate());
        assertEquals(1, stats.getDistribution().stream().filter(b -> b.getBucket().equals("90-100")).findFirst().orElseThrow().getCount());
        assertEquals(1, stats.getDistribution().stream().filter(b -> b.getBucket().equals("1-59")).findFirst().orElseThrow().getCount());
        verify(examAnswerMapper, times(1)).selectList(any());
    }

    @Test
    void attemptPaginationIsBoundedAndExportRetainsIndependentLimit() {
        when(examAttemptMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> inv.getArgument(0));
        var page = service.listAttempts(10L, -3, 100000);
        assertEquals(1, page.getCurrent());
        assertEquals(100, page.getSize());
        assertTrue(service.buildAttemptsCsv(10L).startsWith("会话ID,"));
        verify(examAttemptMapper).selectPage(argThat((Page<ExamAttempt> p) -> p.getSize() == 10000), any());
    }

    @Test
    void configurationIsFetchedOnceForAnAttemptPage() {
        Page<ExamAttempt> page = new Page<>(1, 20, 2);
        page.setRecords(List.of(attempt(2L, 8L, 80), attempt(1L, 9L, 70)));
        when(examAttemptMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(userQuery.findDisplayInfoBatch(any())).thenReturn(Map.of());
        assertEquals(2, service.listAttempts(10L, 1, 20).getRecords().size());
        verify(systemConfigQuery, times(1)).get(eq("exam.gesp-thresholds"), isNull());
    }

    @Test
    void csvNeutralizesSpreadsheetFormulaPrefixesAndQuotesCarriageReturns() {
        assertEquals("'=SUM(A1)", ExamPaperStatsApplicationService.escapeCsv("=SUM(A1)"));
        assertEquals("'  +1", ExamPaperStatsApplicationService.escapeCsv("  +1"));
        assertEquals("'\t@cmd", ExamPaperStatsApplicationService.escapeCsv("\t@cmd"));
        assertEquals("\"a\rb\"", ExamPaperStatsApplicationService.escapeCsv("a\rb"));
        assertEquals("普通昵称", ExamPaperStatsApplicationService.escapeCsv("普通昵称"));
    }

    private ExamAttempt attempt(Long id, Long user, int score) {
        ExamAttempt a = new ExamAttempt(); a.setId(id); a.setUserId(user); a.setTotalScore(score); return a;
    }
}
