package top.everblue.oj.api.submission;

/**
 * 作业提交只读查询契约（教师端作业提交列表）。
 * <p>
 * 实现由 oj-judge 模块提供（数据源为 submission 表），oj-teaching 等模块
 * 通过此契约查询作业维度的提交记录，禁止直接注入 SubmissionMapper。
 * </p>
 */
public interface HomeworkSubmissionQuery {

    /**
     * 分页查询作业提交记录（支持按学生/题目/时间窗过滤）。
     *
     * @param request 作业 ID 与分页过滤条件
     * @return 教师端作业提交视图分页结果
     */
    PageResult<HomeworkSubmissionSummary> findPage(HomeworkSubmissionPageRequest request);
}
