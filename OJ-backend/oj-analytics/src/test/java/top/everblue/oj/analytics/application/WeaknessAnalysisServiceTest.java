package top.everblue.oj.analytics.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.everblue.oj.analytics.entity.ProblemTag;
import top.everblue.oj.analytics.entity.vo.WeaknessResultVO;
import top.everblue.oj.analytics.mapper.ProblemTagMapper;
import top.everblue.oj.api.ai.AiAssistant;
import top.everblue.oj.api.profile.ProfileQuery;
import top.everblue.oj.api.profile.TagStat;
import top.everblue.oj.api.problem.ProblemDigest;
import top.everblue.oj.api.problem.ProblemQuery;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/** 错题归因（seam：WeaknessAnalysisService.analyze）。 */
@ExtendWith(MockitoExtension.class)
class WeaknessAnalysisServiceTest {

    @BeforeAll
    static void initializeTableMetadata() {
        // mock Mapper 不会触发启动扫描，新版 in 查询需要真实的列映射元数据。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), ProblemTag.class);
    }

    @Mock
    private ProfileQuery profileQuery;
    @Mock
    private ProblemTagMapper problemTagMapper;
    @Mock
    private ProblemQuery problemQuery;
    @Mock
    private AiAssistant aiAssistant;

    private WeaknessAnalysisService newService() {
        return new WeaknessAnalysisService(profileQuery, problemTagMapper, problemQuery, aiAssistant);
    }

    private static TagStat stat(Long tagId, String name, int attempted, int passed,
                                int firstTry, int maxDifficulty) {
        return new TagStat(tagId, name, null, "ALGORITHM",
                attempted, passed, firstTry, attempted, 0.0, maxDifficulty);
    }

    @Test
    void picksWeakTagsSortedByMasteryAndSkipsSolved() {
        when(profileQuery.aggregateByTag(any(), any(), any())).thenReturn(List.of(
                stat(1L, "动态规划", 5, 1, 0, 1200),   // 掌握度 ~23，最弱
                stat(2L, "贪心", 3, 2, 1, 1500),       // ~53，弱
                stat(3L, "数学", 4, 4, 4, 2000)));      // 强，排除
        when(profileQuery.findSolvedProblemIds(1L)).thenReturn(Set.of(101L));
        ProblemTag pt = new ProblemTag();
        pt.setProblemId(102L);
        pt.setTagId(1L);
        when(problemTagMapper.selectList(any())).thenReturn(List.of(pt));
        when(problemQuery.findDigestBatch(anyList()))
                .thenReturn(List.of(new ProblemDigest(102L, "背包", 1000, true)));

        WeaknessResultVO result = newService().analyze(1L, 30, false);

        assertEquals(2, result.getWeaknesses().size());
        assertEquals("动态规划", result.getWeaknesses().get(0).getTagName()); // 最弱在前
        assertEquals(1, result.getRecommendations().size());
        assertEquals(102L, result.getRecommendations().get(0).getProblemId());
        assertTrue(result.getRuleBasedAdvice().contains("本周建议优先补强"));
    }

    @Test
    void aiFailureFallsBackToRuleAdvice() {
        when(profileQuery.aggregateByTag(any(), any(), any()))
                .thenReturn(List.of(stat(1L, "图论", 4, 0, 0, 800)));
        when(profileQuery.findSolvedProblemIds(1L)).thenReturn(Set.of());
        when(problemTagMapper.selectList(any())).thenReturn(List.of());
        when(aiAssistant.chat(any(), any())).thenThrow(new RuntimeException("模型不可用"));

        WeaknessResultVO result = newService().analyze(1L, 30, true);

        assertEquals(1, result.getWeaknesses().size());
        assertTrue(result.getAiAdvice().contains("本周建议优先补强"));
    }

    @Test
    void noWeaknessReturnsEmptyAdvice() {
        when(profileQuery.aggregateByTag(any(), any(), any()))
                .thenReturn(List.of(stat(1L, "数学", 4, 4, 4, 2000)));

        WeaknessResultVO result = newService().analyze(1L, 30, true);

        assertEquals(0, result.getWeaknesses().size());
        assertTrue(result.getRuleBasedAdvice().contains("没有明显的薄弱知识点"));
    }
}
