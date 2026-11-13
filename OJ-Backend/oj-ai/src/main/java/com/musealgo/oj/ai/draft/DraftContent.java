package com.musealgo.oj.ai.draft;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 一份题面草稿的完整内容。
 * <p>
 * 字段与题库可编辑字段一一对应，另加样例解释与时限/内存：生成阶段由模型给出建议值，
 * 落库前教师可逐个取消采纳。{@code difficultyScore} 若越界已由解析器收敛到所选档位，
 * 这里不再做二次修正——契约是「解析产出即可直接展示」。
 * </p>
 */
public record DraftContent(
        String title,
        String content,
        String inputDesc,
        String outputDesc,
        String hint,
        Integer difficultyScore,
        List<String> tags,
        List<DraftSample> samples,
        Integer timeLimit,
        Integer memoryLimit) {

    /** 可定向重写的字段：范围之外的字段由解析器整体替换，不做局部重写。 */
    public static final Set<String> REWRITABLE_FIELDS = Set.of("content", "samples", "title", "tags", "hint");

    /** 落库字段白名单，顺序即响应中的 appliedFields 顺序。 */
    public static final List<String> SAVABLE_FIELDS = List.of(
            "title", "content", "inputDesc", "outputDesc", "hint", "tags", "samples");

    public DraftContent {
        title = title == null ? "" : title;
        content = content == null ? "" : content;
        inputDesc = inputDesc == null ? "" : inputDesc;
        outputDesc = outputDesc == null ? "" : outputDesc;
        hint = hint == null ? "" : hint;
        // 过滤 null 元素：请求体里出现 [null] 时 List.copyOf 会 NPE，接口会变成 500 而不是 400
        tags = tags == null ? List.of() : tags.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        samples = samples == null ? List.of()
                : samples.stream().filter(java.util.Objects::nonNull).toList();
    }

    /**
     * 用另一份内容的指定字段替换本内容的同名字段，其余字段原样保留。
     * 定向重写用它合并结果：模型返回整份 JSON，但只有目标字段可信。
     */
    public DraftContent replaceField(String field, DraftContent replacement) {
        if (replacement == null || field == null) {
            return this;
        }
        return switch (field) {
            case "title" -> new DraftContent(replacement.title, content, inputDesc, outputDesc, hint,
                    difficultyScore, tags, samples, timeLimit, memoryLimit);
            case "content" -> new DraftContent(title, replacement.content, inputDesc, outputDesc, hint,
                    difficultyScore, tags, samples, timeLimit, memoryLimit);
            case "samples" -> new DraftContent(title, content, inputDesc, outputDesc, hint,
                    difficultyScore, tags, replacement.samples, timeLimit, memoryLimit);
            case "tags" -> new DraftContent(title, content, inputDesc, outputDesc, hint,
                    difficultyScore, replacement.tags, samples, timeLimit, memoryLimit);
            case "hint" -> new DraftContent(title, content, inputDesc, outputDesc, replacement.hint,
                    difficultyScore, tags, samples, timeLimit, memoryLimit);
            case "inputDesc" -> new DraftContent(title, content, replacement.inputDesc, outputDesc, hint,
                    difficultyScore, tags, samples, timeLimit, memoryLimit);
            case "outputDesc" -> new DraftContent(title, content, inputDesc, replacement.outputDesc, hint,
                    difficultyScore, tags, samples, timeLimit, memoryLimit);
            default -> this;
        };
    }

    /** 供定向重写比较「模型到底改了没有」：只比较目标字段的规范化文本。 */
    public String textOf(String field) {
        return switch (field) {
            case "title" -> title;
            case "content" -> content;
            case "inputDesc" -> inputDesc;
            case "outputDesc" -> outputDesc;
            case "hint" -> hint;
            case "tags" -> String.join(",", tags);
            case "samples" -> {
                List<String> parts = new ArrayList<>();
                for (DraftSample sample : samples) {
                    // 解释也是样例的一部分：只解释改动的重写不应被判成「没有变化」
                    parts.add(sample.input() + "\u0000" + sample.output() + "\u0000" + sample.explanation());
                }
                yield String.join("\u0001", parts);
            }
            default -> "";
        };
    }
}
