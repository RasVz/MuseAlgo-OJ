package top.everblue.oj.problem.importer;

import java.util.List;

/**
 * 从 FPS 文件解析出的一道题，尚未落库。
 * <p>
 * 这是解析层与持久层之间的中间表示：字段名对齐文件语义（{@code timeLimitMillis}、
 * {@code memoryLimitMegabytes}），单位已统一，转换逻辑集中在
 * {@link FpsProblemSetParser}，落库时不再做单位换算。
 * </p>
 * <p>
 * {@code warnings} 是本类型存在的关键理由：导入并不保证数据完美，
 * 所有被降级处理的问题（截断、配对丢弃、资源未导入）都记在这里，
 * 由上层展示给用户，而不是静默丢弃。
 * </p>
 *
 * @param title              题面标题，缺失时由解析器生成占位标题
 * @param timeLimitMillis    时间限制（毫秒），无法解析时为默认值
 * @param memoryLimitMegabytes 内存限制（MB），无法解析时为默认值
 */
public record FpsProblem(
        String title,
        String description,
        String inputDescription,
        String outputDescription,
        List<FpsSample> samples,
        String hint,
        String source,
        int timeLimitMillis,
        int memoryLimitMegabytes,
        List<FpsTestCase> testCases,
        List<String> warnings
) {
}
