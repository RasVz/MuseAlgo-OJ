package top.everblue.oj.api.problem;

/**
 * 判题用例视图 — 跨模块只读投射，判题链路的用例数据载体。
 * <p>
 * input/output 为最终可用文本（文件存储优先、旧列回退由实现方解析完成），
 * 判题模块（oj-judge）不感知存储细节，也不接触 oj-problem 的实体/Mapper。
 * </p>
 */
public record TestCaseView(
        Long id,
        Long problemId,
        String input,
        String output,
        Integer score,
        Integer subtaskNo,
        Integer caseOrder,
        Byte status
) {
}
