package com.musealgo.oj.analytics.report;

import org.junit.jupiter.api.Test;
import com.musealgo.oj.analytics.domain.*;
import com.musealgo.oj.api.ai.AiAssistant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiAdviceBuilderTest {
    @Test void reportsUseRulesWithoutSendingStudentDataByDefault() {
        AiAssistant ai = mock(AiAssistant.class);
        var weak = new TagMastery(1L, "动态规划", null, "dp", 4, 1, 0, 8, 0, 1200, 25, MasteryLevel.of(25));
        var profile = new StudentProfile(7L, "私有姓名", 9L, "班级", LocalDate.now().minusDays(6), LocalDate.now(),
                null, List.of(weak), Map.of(), Map.of(), List.of(weak));
        String advice = new AiAdviceBuilder(ai).buildAdvice(profile);
        assertTrue(advice.contains("动态规划"));
        assertFalse(advice.contains("私有姓名"));
        verifyNoInteractions(ai);
    }
}
