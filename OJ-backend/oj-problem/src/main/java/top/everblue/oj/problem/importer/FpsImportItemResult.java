package top.everblue.oj.problem.importer;

/**
 * 导入报告中的单题条目，直接序列化给前端展示。
 * <p>
 * 与 {@link FpsParsedItem} 分开是因为二者生命周期不同：后者是解析阶段的中间态，
 * 这里记录的是「解析 + 落库」整条链路的最终结论（例如解析成功但建表失败）。
 * </p>
 *
 * @param position      题目在本次导入中的序号
 * @param problemId     落库后的题目 ID；失败时为 null（前端据此决定能否跳转）
 * @param testCaseCount 该题成功写入的测试点数量
 * @param message       面向用户的说明，成功时可能附带解析 warning
 */
public record FpsImportItemResult(
        int position,
        String title,
        Long problemId,
        int testCaseCount,
        boolean success,
        String message
) {
    public static FpsImportItemResult success(int position, String title, Long problemId, int testCaseCount) {
        return new FpsImportItemResult(position, title, problemId, testCaseCount, true, "导入成功");
    }

    public static FpsImportItemResult success(int position, String title, Long problemId,
                                              int testCaseCount, String message) {
        return new FpsImportItemResult(position, title, problemId, testCaseCount, true, message);
    }

    public static FpsImportItemResult failure(int position, String title, String message) {
        return new FpsImportItemResult(position, title, null, 0, false, message);
    }
}
