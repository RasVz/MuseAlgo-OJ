package top.everblue.oj.analytics.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.everblue.oj.analytics.domain.MasteryCalculator;
import top.everblue.oj.analytics.domain.MasteryLevel;
import top.everblue.oj.analytics.entity.ProblemTag;
import top.everblue.oj.analytics.entity.vo.WeaknessResultVO;
import top.everblue.oj.analytics.mapper.ProblemTagMapper;
import top.everblue.oj.api.ai.AiAssistant;
import top.everblue.oj.api.profile.ProfileQuery;
import top.everblue.oj.api.profile.TagStat;
import top.everblue.oj.api.problem.ProblemDigest;
import top.everblue.oj.api.problem.ProblemQuery;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 错题归因 + 个性化训练计划（A3）。
 * <p>
 * 规则层：基于近 N 天提交按知识点算掌握度，挑出薄弱点（mastery < 55），
 * 再从薄弱知识点推荐未 AC 的公开题（按难度升序）；
 * AI 层：把薄弱点喂给模型生成具体可执行的训练建议，失败时回退规则文案。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeaknessAnalysisService {

    private static final int WEAK_MASTERY = 55;
    private static final int MAX_WEAKNESSES = 5;
    private static final int MAX_RECOMMENDATIONS = 5;
    private static final int DIFFICULTY_CEILING = 2400;

    private final ProfileQuery profileQuery;
    private final ProblemTagMapper problemTagMapper;
    private final ProblemQuery problemQuery;
    private final AiAssistant aiAssistant;

    public WeaknessResultVO analyze(Long userId, int days, boolean withAi) {
        LocalDateTime from = LocalDateTime.now().minusDays(Math.max(days, 1));
        List<TagStat> stats = profileQuery.aggregateByTag(userId, from, LocalDateTime.now());

        WeaknessResultVO result = new WeaknessResultVO();
        List<WeaknessResultVO.WeaknessVO> weaknesses = stats.stream()
                .filter(s -> s.attempted() > 0)
                .map(this::toWeakness)
                .filter(w -> w.getMastery() < WEAK_MASTERY)
                .sorted(Comparator.comparingInt(WeaknessResultVO.WeaknessVO::getMastery))
                .limit(MAX_WEAKNESSES)
                .toList();
        result.setWeaknesses(new ArrayList<>(weaknesses));

        result.setRecommendations(buildRecommendations(userId, weaknesses));
        result.setRuleBasedAdvice(buildRuleAdvice(weaknesses));

        if (withAi && !weaknesses.isEmpty()) {
            result.setAiAdvice(buildAiAdvice(stats, weaknesses));
        } else {
            result.setAiAdvice(result.getRuleBasedAdvice());
        }
        return result;
    }

    private WeaknessResultVO.WeaknessVO toWeakness(TagStat stat) {
        int mastery = MasteryCalculator.mastery(stat);
        WeaknessResultVO.WeaknessVO vo = new WeaknessResultVO.WeaknessVO();
        vo.setTagId(stat.tagId());
        vo.setTagName(stat.tagName());
        vo.setMastery(mastery);
        vo.setFailCount(Math.max(0, stat.attempted() - stat.passed()));
        vo.setLevel(MasteryLevel.of(mastery).getLabel());
        return vo;
    }

    private List<WeaknessResultVO.RecommendationVO> buildRecommendations(
            Long userId, List<WeaknessResultVO.WeaknessVO> weaknesses) {
        if (weaknesses.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> solved = profileQuery.findSolvedProblemIds(userId);
        List<Long> weaknessTagIds = weaknesses.stream()
                .map(WeaknessResultVO.WeaknessVO::getTagId).toList();
        List<Long> candidateIds = problemTagMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProblemTag>()
                                .in(ProblemTag::getTagId, weaknessTagIds))
                .stream().map(ProblemTag::getProblemId).distinct().toList();
        if (candidateIds.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, ProblemDigest> digestMap = problemQuery.findDigestBatch(candidateIds).stream()
                .collect(Collectors.toMap(ProblemDigest::id, Function.identity()));
        return candidateIds.stream()
                .filter(id -> !solved.contains(id))
                .map(digestMap::get)
                .filter(d -> d != null && d.isPublic() != null && d.isPublic())
                .filter(d -> d.difficulty() == null || d.difficulty() <= DIFFICULTY_CEILING)
                .sorted(Comparator.comparing(d -> d.difficulty() == null ? 0 : d.difficulty()))
                .limit(MAX_RECOMMENDATIONS)
                .map(d -> {
                    WeaknessResultVO.RecommendationVO vo = new WeaknessResultVO.RecommendationVO();
                    vo.setProblemId(d.id());
                    vo.setTitle(d.title());
                    vo.setDifficulty(d.difficulty());
                    return vo;
                })
                .toList();
    }

    private String buildRuleAdvice(List<WeaknessResultVO.WeaknessVO> weaknesses) {
        if (weaknesses.isEmpty()) {
            return "最近没有明显的薄弱知识点，继续保持刷题节奏，注意一次通过率。";
        }
        String tags = weaknesses.stream()
                .map(w -> w.getTagName() + "（掌握度 " + w.getMastery() + "，错 " + w.getFailCount() + " 题）")
                .collect(Collectors.joining("；"));
        return "本周建议优先补强：" + tags + "。每天做 1~2 道对应知识点的题，先看思路再写码，"
                + "提交前用最小样例自测一遍，提高一次通过率。";
    }

    private String buildAiAdvice(List<TagStat> stats, List<WeaknessResultVO.WeaknessVO> weaknesses) {
        String system = """
                你是资深信奥教练。基于学生薄弱知识点数据，生成一段 120~160 字的中文训练建议：
                具体到每天做什么、先补哪个知识点、用什么方法（如最小样例自测、限时练习），
                语气鼓励，不说教，不评价人格。只输出建议正文。""";
        int totalSubmissions = stats.stream().mapToInt(TagStat::totalSubmissions).sum();
        long acceptedProblems = stats.stream().mapToLong(TagStat::passed).sum();
        String user = "近 30 天：提交 " + totalSubmissions + " 次，AC " + acceptedProblems + " 题。\n"
                + "薄弱知识点（掌握度 0-100）："
                + weaknesses.stream()
                .map(w -> w.getTagName() + " " + w.getMastery() + " 分，错 " + w.getFailCount() + " 题")
                .collect(Collectors.joining("，"));
        try {
            String advice = aiAssistant.chat(system, user);
            return (advice == null || advice.isBlank()) ? buildRuleAdvice(weaknesses) : advice;
        } catch (Exception e) {
            log.warn("【错题归因】AI 建议失败，回退规则文案: {}", e.getMessage());
            return buildRuleAdvice(weaknesses);
        }
    }
}
