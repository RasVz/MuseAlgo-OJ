package top.everblue.oj.api.problem;

import top.everblue.oj.api.submission.PageResult;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 题单只读查询契约 — 跨模块读取 {@code problem_list} 数据的唯一入口。
 * <p>
 * 实现由 oj-problem 模块提供（数据归属方），消费者禁止直接注入 ProblemListService/Mapper。
 * </p>
 */
public interface ProblemListQuery {

    /** 按 ID 查询题单视图（不存在返回空）。 */
    Optional<ProblemListView> findById(Long problemListId);

    /** 按班级 ID 批量查询题单（按创建时间倒序），班级作业列表场景用。 */
    List<ProblemListView> findByClassIds(Collection<Long> classIds);

    /** 分页查询班级题单（type=3，按创建时间倒序）。 */
    PageResult<ProblemListView> findClassProblemListPage(Long classId, int page, int size);

    /** 按 ID 集合 + 标题关键字分页查询（收藏题单列表用，keyword 可空）。 */
    PageResult<ProblemListView> findPageByIds(Collection<Long> ids, String keyword, int page, int size);

    /** 作业完成情况概览（班级 × 作业维度，含学生/题目/格子统计）。 */
    Map<String, Object> getHomeworkStatusOverview(Long classId, Long homeworkId);
}
