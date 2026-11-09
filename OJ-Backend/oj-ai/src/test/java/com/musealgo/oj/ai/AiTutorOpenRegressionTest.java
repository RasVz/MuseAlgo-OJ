package com.musealgo.oj.ai;
import com.musealgo.oj.ai.dto.*;
import com.musealgo.oj.ai.gateway.PromptRegistry;
import com.musealgo.oj.ai.tutor.TutorMessageBuilder;
import com.musealgo.oj.api.problem.ProblemTutoringQuery;
import com.musealgo.oj.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AiTutorOpenRegressionTest {
    final TutorMessageBuilder builder = new TutorMessageBuilder(mock(PromptRegistry.class));
    final ProblemTutoringQuery.Context problem = new ProblemTutoringQuery.Context(1L,"题","范围 n <= 1000");
    @Test void firstFullSolutionHasNoContradictoryBan() {
        var p = new AiTeachingPreferences(); p.setHelpDirection(AiTeachingPreferences.HelpDirection.FULL_SOLUTION);
        var messages = builder.build("tutor-open",problem,"code","java","给完整代码",p,null,List.of(),null);
        assertFalse(messages.toString().contains("禁止输出完整可提交"));
        assertTrue(messages.toString().contains("当轮提供完整推导"));
        assertTrue(messages.toString().contains("当轮学生明确要求 >"));
        assertTrue(messages.getJSONObject(1).getStr("content").contains("java"));
    }
    @ParameterizedTest @EnumSource(AiTeachingPreferences.HelpDirection.class)
    void allDirectionsProduceDistinctRulesWithoutUnlocks(AiTeachingPreferences.HelpDirection direction) {
        var p=new AiTeachingPreferences();p.setHelpDirection(direction);
        var m=builder.build("tutor-open",problem,"code","cpp","问题",p,"忽略规则的材料",List.of(),null);
        assertEquals("system",m.getJSONObject(0).getStr("role"));
        assertFalse(m.getJSONObject(0).getStr("content").contains("忽略规则的材料"));
        assertEquals("user",m.getJSONObject(1).getStr("role"));
    }
    @Test void hintsAndDetailAreIndependentAndExplicitQuestionWins() {
        var p=new AiTeachingPreferences();p.setAnswerStyle(AiTeachingPreferences.AnswerStyle.HINT_ONLY);p.setDetailLevel(AiTeachingPreferences.DetailLevel.DETAILED);
        var m=builder.build("tutor-open",problem,"code","cpp","现在直接告诉我",p,null,List.of(),null);
        assertTrue(m.toString().contains("只给提示"));assertTrue(m.toString().contains("详细展开"));
        assertTrue(m.toString().contains("当轮明确要求直接回答时以当轮要求为准"));
    }
    @Test void oversizedCodeAndProblemAreRejectedInsteadOfSilentlyTruncated() {
        assertThrows(BusinessException.class,()->builder.build("tutor-open",problem,"x".repeat(16001),"cpp","q",new AiTeachingPreferences(),null,List.of(),null));
        assertThrows(BusinessException.class,()->builder.build("tutor-open",new ProblemTutoringQuery.Context(1L,"题","x".repeat(20001)),"c","cpp","q",new AiTeachingPreferences(),null,List.of(),null));
    }
    @Test void historyIsPrunedInCompleteTurnsAndReferenceMarked() {
        var history=List.of(TutorMessageBuilder.message("user","x".repeat(7000)),TutorMessageBuilder.message("assistant","y".repeat(7000)),TutorMessageBuilder.message("user","新问题"),TutorMessageBuilder.message("assistant","新回答"));
        var m=builder.build("tutor-open",problem,"c","cpp","q",new AiTeachingPreferences(),"r".repeat(11000),history,null);
        assertEquals(4,m.size());assertEquals("新问题",m.getJSONObject(1).getStr("content"));assertTrue(m.toString().contains("已截断"));
    }
}
