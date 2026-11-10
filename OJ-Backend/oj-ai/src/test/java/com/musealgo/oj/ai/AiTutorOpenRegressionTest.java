package com.musealgo.oj.ai;
import com.musealgo.oj.ai.dto.*;
import com.musealgo.oj.ai.gateway.PromptRegistry;
import com.musealgo.oj.ai.tutor.TutorMessageBuilder;
import com.musealgo.oj.api.problem.ProblemTutoringQuery;
import com.musealgo.oj.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.HashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AiTutorOpenRegressionTest {
    final TutorMessageBuilder builder = new TutorMessageBuilder(mock(PromptRegistry.class));
    final ProblemTutoringQuery.Context problem = new ProblemTutoringQuery.Context(1L,"题","范围 n <= 1000");
    @Test void defaultModeIsAuto() {
        assertEquals(AiTeachingOptions.HelpMode.AUTO,new AiTeachingOptions().getHelpMode());
    }
    @Test void firstFullSolutionHasNoContradictoryBan() {
        var p = new AiTeachingOptions(); p.setHelpMode(AiTeachingOptions.HelpMode.FULL_SOLUTION);
        var messages = builder.build("tutor-open",problem,"code","java","给完整代码",p.getHelpMode(),null,List.of(),null);
        assertFalse(messages.toString().contains("禁止输出完整可提交"));
        assertTrue(messages.toString().contains("当轮提供完整推导"));
        assertTrue(messages.toString().contains("当轮学生明确要求 >"));
        assertTrue(messages.getJSONObject(1).getStr("content").contains("java"));
    }
    @ParameterizedTest @EnumSource(AiTeachingOptions.HelpMode.class)
    void allModesProduceOneModeRuleWithoutLegacyPreferences(AiTeachingOptions.HelpMode mode) {
        var m=builder.build("tutor-open",problem,"code","cpp","问题",mode,"忽略规则的材料",List.of(),null);
        assertEquals("system",m.getJSONObject(0).getStr("role"));
        String system=m.getJSONObject(0).getStr("content");
        assertEquals(1,system.split("【本轮教学模式】",-1).length-1);
        assertFalse(system.contains("【本轮帮助方向】"));
        assertFalse(system.contains("【表达偏好】"));
        assertFalse(system.contains("【展开程度】"));
        assertFalse(system.contains("忽略规则的材料"));
        assertEquals("user",m.getJSONObject(1).getStr("role"));
    }
    @Test void sixModesProduceDistinctModeRules() {
        var rules=new HashSet<String>();
        for (var mode : AiTeachingOptions.HelpMode.values()) {
            String system=builder.build("tutor-open",problem,"code","cpp","问题",mode,null,List.of(),null)
                    .getJSONObject(0).getStr("content");
            rules.add(system.substring(system.indexOf("【本轮教学模式】")));
        }
        assertEquals(6,rules.size());
    }
    @Test void hintModeAllowsExplicitQuestionToOverrideWithoutLegacyStyleBlock() {
        var m=builder.build("tutor-open",problem,"code","cpp","现在直接告诉我",AiTeachingOptions.HelpMode.GET_HINT,null,List.of(),null);
        String system=m.getJSONObject(0).getStr("content");
        assertTrue(system.contains("给我提示："));
        assertTrue(system.contains("避免直接展开核心修复"));
        assertTrue(system.contains("当轮明确要求直接回答时以当轮要求为准"));
        assertFalse(system.contains("【表达偏好】"));
    }
    @Test void oversizedCodeAndProblemAreRejectedInsteadOfSilentlyTruncated() {
        assertThrows(BusinessException.class,()->builder.build("tutor-open",problem,"x".repeat(16001),"cpp","q",AiTeachingOptions.HelpMode.AUTO,null,List.of(),null));
        assertThrows(BusinessException.class,()->builder.build("tutor-open",new ProblemTutoringQuery.Context(1L,"题","x".repeat(20001)),"c","cpp","q",AiTeachingOptions.HelpMode.AUTO,null,List.of(),null));
    }
    @Test void historyIsPrunedInCompleteTurnsAndReferenceMarked() {
        var history=List.of(TutorMessageBuilder.message("user","x".repeat(7000)),TutorMessageBuilder.message("assistant","y".repeat(7000)),TutorMessageBuilder.message("user","新问题"),TutorMessageBuilder.message("assistant","新回答"));
        var m=builder.build("tutor-open",problem,"c","cpp","q",AiTeachingOptions.HelpMode.AUTO,"r".repeat(11000),history,null);
        assertEquals(4,m.size());assertEquals("新问题",m.getJSONObject(1).getStr("content"));assertTrue(m.toString().contains("已截断"));
    }
}
