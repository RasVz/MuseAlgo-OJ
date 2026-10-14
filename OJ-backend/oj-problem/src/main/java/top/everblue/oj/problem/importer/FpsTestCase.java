package top.everblue.oj.problem.importer;

/**
 * FPS 文件中的一组私有测试数据，与 {@link FpsSample} 的区别是仅教师可见。
 * <p>
 * 内容<b>原样保留</b>，不在解析阶段做 trim 或换行归一化：测试数据按字节比较，
 * 解析层一旦"顺手清理"空白就会让原本正确的题目判错。
 * </p>
 */
public record FpsTestCase(String input, String output) {
}
