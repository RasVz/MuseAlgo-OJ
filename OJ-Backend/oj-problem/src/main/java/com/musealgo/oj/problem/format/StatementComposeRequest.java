package com.musealgo.oj.problem.format;

/**
 * 编辑器保存。
 * <p>
 * 两种写入方式：
 * </p>
 * <ul>
 *   <li>{@code SAVE}：把编辑区内容写回当前打开的那个版本（就地覆盖），版本属性不变；</li>
 *   <li>{@code SAVE_AS_NEW}：基于编辑区内容新建一条版本，属性由 {@code source} 指定。</li>
 * </ul>
 * <p>
 * 两者都会把该版本设为「当前」并回写题目。质量检查结果只写进快照，不阻断保存。
 * </p>
 *
 * @param mode          SAVE / SAVE_AS_NEW，空值按 SAVE_AS_NEW 处理
 * @param baseVersionId 当前打开的版本；SAVE 时必填
 * @param source        INITIAL / AI / MANUAL，仅 SAVE_AS_NEW 有意义
 * @param note          版本备注
 * @param formatted     完整题面内容
 */
public record StatementComposeRequest(
        String mode,
        Long baseVersionId,
        String source,
        String note,
        ProblemFormattedContent formatted
) {
    public boolean saveInPlace() {
        return "SAVE".equalsIgnoreCase(mode);
    }

    public String normalizedSource() {
        if ("INITIAL".equalsIgnoreCase(source) || "AI".equalsIgnoreCase(source)) {
            return source.toUpperCase();
        }
        return "MANUAL";
    }
}
