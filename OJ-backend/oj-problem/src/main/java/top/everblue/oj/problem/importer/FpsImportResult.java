package top.everblue.oj.problem.importer;

import java.util.List;

/**
 * 一次批量导入的汇总结果。
 * <p>
 * {@code total} / {@code succeeded} / {@code failed} 是给用户看的汇总数字，
 * {@code items} 是逐题明细。刻意保留失败项而不只返回成功数：
 * 部分成功时只给一个数字，用户无法知道该去哪里修题。
 * </p>
 *
 * @param total     本次处理的题目总数（解析成功数 + 解析失败数）
 * @param succeeded 成功落库的题目数
 * @param failed    处理失败的题目数，满足 {@code total == succeeded + failed}
 */
public record FpsImportResult(int total, int succeeded, int failed, List<FpsImportItemResult> items) {
}
