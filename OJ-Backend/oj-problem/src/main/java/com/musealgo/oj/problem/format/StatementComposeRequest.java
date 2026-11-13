package com.musealgo.oj.problem.format;

/**
 * 编辑器保存：把当前编辑区内容写成新的 LIVE 版本。
 * <p>
 * 与「应用候选」的区别：这里没有字段勾选，内容就是用户在编辑器里看到的最终结果；
 * 质量检查结果只作为提示，不再阻断保存——人已经逐段确认过内容了，
 * 自动应用（无人值守）才需要闸门。
 * </p>
 *
 * @param baseVersionId 这次编辑基于哪个版本；为空表示基于当前题面
 * @param source        MANUAL（手工编辑）/ AI_APPLIED（AI 生成后又改过）
 * @param note          版本备注
 * @param formatted     完整题面内容
 */
public record StatementComposeRequest(
        Long baseVersionId,
        String source,
        String note,
        ProblemFormattedContent formatted
) {
    public boolean aiApplied() {
        return "AI_APPLIED".equalsIgnoreCase(source);
    }
}
