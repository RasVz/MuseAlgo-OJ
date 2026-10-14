package top.everblue.oj.api.contest;

import top.everblue.oj.api.submission.PageResult;

/**
 * 班级比赛只读查询契约 — 教学端查看班级关联比赛的唯一入口。
 * <p>
 * 实现由 oj-contest 模块提供（数据归属方），oj-teaching 等模块
 * 通过此契约消费班级比赛列表，禁止直接注入 ContestService/Mapper。
 * </p>
 */
public interface ClassContestQuery {

    /**
     * 分页查询班级关联比赛（支持关键字与排序）。
     *
     * @param request 班级 ID 与分页/过滤条件
     * @return 班级比赛摘要分页结果
     */
    PageResult<ClassContestSummary> findPage(ClassContestPageRequest request);
}
