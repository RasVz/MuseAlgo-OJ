package top.everblue.oj.api.problem;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 题目查询契约 — 跨模块只读访问 Problem 数据的唯一入口。
 */
public interface ProblemQuery {

    /** 按 ID 查询题目标题 */
    Optional<String> findTitleById(Long problemId);

    /** 批量查询题目标题，返回 problemId → title 映射 */
    Map<Long, String> findTitleBatch(Collection<Long> problemIds);

    /** 批量查询题目摘要（含难度），供报告推荐题单排序使用 */
    List<ProblemDigest> findDigestBatch(Collection<Long> problemIds);

    /** 查询题目时限与内存限制，判题用。返回 [timeLimitMs, memoryLimitKb] */
    int[] findProblemLimits(Long problemId);

    /**
     * 按题目 ID 查询判题用例（已按 case_order 升序）。
     * <p>
     * input/output 为最终可用文本（文件存储优先、旧列回退由实现方解析），
     * 判题模块不感知存储细节。用例为空时应由调用方走 fail 流程。
     * </p>
     */
    List<TestCaseView> findTestCases(Long problemId);

    /** 题目是否存在且未删除（社区评论/关联校验用）。 */
    boolean existsNotDeleted(Long problemId);
}
