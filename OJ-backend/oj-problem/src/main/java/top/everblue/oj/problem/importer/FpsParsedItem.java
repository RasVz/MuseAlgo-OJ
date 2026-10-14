package top.everblue.oj.problem.importer;

/**
 * 单道题的解析结果，成功或失败二选一。
 * <p>
 * 用一个类型同时承载两种结果（而非返回 {@code Optional} 或抛异常），
 * 是为了让「哪一道题失败了」这件事能跟着结果一起流到前端——
 * {@code position} 与 {@code title} 在两种结果下都有值，
 * 用户才能在导入报告里定位到具体题目。
 * </p>
 *
 * @param position 在文件中的序号（从 1 开始），ZIP 多文件时为跨文件全局序号
 * @param title    题目标题；解析失败时可能只有占位标题
 * @param problem  解析成功的题目，失败时为 null
 * @param error    失败原因，成功时为 null
 */
public record FpsParsedItem(int position, String title, FpsProblem problem, String error) {
    public boolean success() {
        return problem != null;
    }

    public static FpsParsedItem success(int position, FpsProblem problem) {
        return new FpsParsedItem(position, problem.title(), problem, null);
    }

    public static FpsParsedItem failure(int position, String title, String error) {
        return new FpsParsedItem(position, title, null, error);
    }
}
