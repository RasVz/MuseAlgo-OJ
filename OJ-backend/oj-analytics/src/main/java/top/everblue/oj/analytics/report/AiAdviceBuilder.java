package top.everblue.oj.analytics.report;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.everblue.oj.analytics.domain.StudentProfile;
import top.everblue.oj.analytics.domain.TagMastery;
import top.everblue.oj.api.ai.AiAssistant;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 薄弱环节练习建议生成器。
 * <p>
 * 优先调用 {@link AiAssistant#chat} 生成教练口吻的建议；
 * AI 不可用（未配置 key / 超时 / 返回降级文案）时回退到本地模板文案，
 * 保证报告一定出得来。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiAdviceBuilder {

    private final AiAssistant aiAssistant;

    private static final String SYSTEM_PROMPT =
            "你是一位资深的信息学竞赛（信奥）教练，正在为学生家长撰写周报中的「练习建议」部分。\n" +
            "要求：\n" +
            "1. 语气专业、温和、鼓励，面向家长而非学生本人；\n" +
            "2. 针对给出的薄弱知识点，每个给出 1-2 句具体的改进建议（如建议练习的题型方向、常见易错点）；\n" +
            "3. 结尾用一句话总结本周整体表现并给予鼓励；\n" +
            "4. 不要输出代码，不要使用 Markdown 标题，控制在 150 字以内。";

    /** AiService 在 API 失败时返回的降级文案，出现这些前缀视为 AI 不可用 */
    private static final List<String> AI_FALLBACK_MARKERS = List.of(
            "暂时无法生成智能建议", "网络异常", "糟糕，教练的大脑");

    /**
     * 生成建议文案。永远返回非空字符串。
     */
    public String buildAdvice(StudentProfile profile) {
        List<TagMastery> weakPoints = profile.weakPoints();
        if (weakPoints == null || weakPoints.isEmpty()) {
            return "本周各知识点掌握情况良好，无明显薄弱环节。建议保持当前学习节奏，"
                    + "可适当尝试更高难度的题目以拓宽解题思路。";
        }

        String aiAdvice = tryAiAdvice(profile, weakPoints);
        if (aiAdvice != null) {
            return aiAdvice;
        }
        return templateAdvice(weakPoints);
    }

    private String tryAiAdvice(StudentProfile profile, List<TagMastery> weakPoints) {
        try {
            String weakDesc = weakPoints.stream()
                    .limit(5)
                    .map(t -> String.format("%s（掌握度 %d 分，尝试 %d 题，通过 %d 题）",
                            t.tagName(), t.mastery(), t.attempted(), t.passed()))
                    .collect(Collectors.joining("；"));

            String userPrompt = String.format(
                    "学生：%s；统计周期：%s 至 %s。\n本周薄弱知识点：%s。\n请生成练习建议。",
                    profile.studentName() != null ? profile.studentName() : "同学",
                    profile.periodStart(), profile.periodEnd(), weakDesc);

            String result = aiAssistant.chat(SYSTEM_PROMPT, userPrompt);
            if (result == null || result.isBlank() || isFallbackText(result)) {
                log.info("AI 建议不可用，降级为模板文案");
                return null;
            }
            return result.trim();
        } catch (Exception e) {
            log.warn("AI 建议生成异常，降级为模板文案: {}", e.getMessage());
            return null;
        }
    }

    private boolean isFallbackText(String text) {
        return AI_FALLBACK_MARKERS.stream().anyMatch(text::startsWith);
    }

    /**
     * 无 AI 时的本地模板文案，同样给出可执行的改进方向。
     */
    private String templateAdvice(List<TagMastery> weakPoints) {
        StringBuilder sb = new StringBuilder();
        sb.append("本周孩子在以下知识点还需加强：");
        for (int i = 0; i < Math.min(3, weakPoints.size()); i++) {
            TagMastery t = weakPoints.get(i);
            sb.append(String.format("「%s」（掌握度 %d 分）", t.tagName(), t.mastery()));
            if (i < Math.min(3, weakPoints.size()) - 1) {
                sb.append("、");
            } else {
                sb.append("。");
            }
        }
        sb.append("建议先回顾对应的基础概念，再从简单题开始循序练习，"
                + "重点总结错题原因。坚持每周积累，薄弱点会逐步改善。");
        return sb.toString();
    }
}