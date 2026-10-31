package com.musealgo.oj.analytics.application;

import org.junit.jupiter.api.Test;
import com.musealgo.oj.analytics.domain.StudentProfile;
import com.musealgo.oj.analytics.mapper.ProblemTagMapper;
import com.musealgo.oj.analytics.mapper.ProfileSnapshotMapper;
import com.musealgo.oj.analytics.report.AiAdviceBuilder;
import com.musealgo.oj.analytics.report.FreemarkerHtmlBuilder;
import com.musealgo.oj.api.problem.ProblemQuery;
import com.musealgo.oj.api.profile.ProfileQuery;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportApplicationServiceTest {

    @Test
    void preparesHtmlOutsideFinalizerAndDoesNotWriteSnapshot() {
        var profiles = mock(ProfileApplicationService.class);
        var html = mock(FreemarkerHtmlBuilder.class);
        var advice = mock(AiAdviceBuilder.class);
        var start = LocalDate.of(2026, 10, 12);
        var end = start.plusDays(6);
        var profile = new StudentProfile(1L, "student", 2L, "class", start, end,
                null, List.of(), Map.of(), Map.of(), List.of());

        when(profiles.buildForReportInternal(1L, 2L, start, end)).thenReturn(profile);
        when(advice.buildAdvice(profile)).thenReturn("local advice");
        when(html.buildHtml(eq(profile), eq("local advice"), anyList(), anyMap()))
                .thenReturn("<html>private report</html>");

        var reports = new ReportApplicationService(profiles, mock(ProfileQuery.class),
                mock(ProblemQuery.class), mock(ProblemTagMapper.class),
                mock(ProfileSnapshotMapper.class), html, advice);
        var prepared = reports.prepareWeeklyReportInternal(1L, 2L, start, end);

        assertSame(profile, prepared.profile());
        assertEquals("<html>private report</html>", prepared.html());
        verify(profiles, never()).saveSnapshot(profile);
    }

    @Test
    void recommendationDifficultyLabelsAreReadableBandsWithoutGaps() {
        assertEquals("未评定", ReportApplicationService.difficultyLabel(null));
        assertEquals("未评定", ReportApplicationService.difficultyLabel(700));
        assertEquals("入门", ReportApplicationService.difficultyLabel(800));
        assertEquals("入门", ReportApplicationService.difficultyLabel(999));
        assertEquals("入门+", ReportApplicationService.difficultyLabel(1000));
        assertEquals("普及-", ReportApplicationService.difficultyLabel(1200));
        assertEquals("普及", ReportApplicationService.difficultyLabel(1400));
        assertEquals("普及+", ReportApplicationService.difficultyLabel(1700));
        assertEquals("提高", ReportApplicationService.difficultyLabel(2000));
        assertEquals("提高+", ReportApplicationService.difficultyLabel(2200));
        assertEquals("省选-", ReportApplicationService.difficultyLabel(2400));
        assertEquals("省选-", ReportApplicationService.difficultyLabel(2500));
        assertEquals("省选 / NOI-", ReportApplicationService.difficultyLabel(2600));
        assertEquals("NOI 及以上", ReportApplicationService.difficultyLabel(2800));
        assertEquals("NOI 及以上", ReportApplicationService.difficultyLabel(3000));
    }
}
