package top.everblue.oj.api.problem;

/**
 * 题单写操作契约 — 跨模块修改 {@code problem_list} 数据的唯一入口。
 * <p>
 * 实现由 oj-problem 模块提供（数据归属方），消费者（oj-teaching 班级题单、
 * oj-community 收藏计数）禁止直接注入 ProblemListService/Mapper。
 * </p>
 */
public interface ProblemListCommand {

    /**
     * 创建班级题单（type=3），返回新题单 ID。
     *
     * @param request 班级题单创建请求（classId/creatorId 必填）
     */
    Long createClassProblemList(NewClassProblemList request);

    /** 更新题单标题与描述（防篡改：不更新归属/班级/类型/时间之外的其他字段）。 */
    void updateClassProblemList(Long id, String title, String description);

    /** 删除题单。 */
    void removeById(Long id);

    /** 增减题单收藏数（delta 为 +1 或 -1，下限保护为 0）。 */
    void adjustFavoriteCount(Long id, int delta);

    /** 班级题单创建请求。 */
    record NewClassProblemList(Long classId, Long creatorId, String title, String description) {
        public NewClassProblemList {
            if (classId == null) {
                throw new IllegalArgumentException("classId 不能为空");
            }
            if (creatorId == null) {
                throw new IllegalArgumentException("creatorId 不能为空");
            }
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("题单名称不能为空");
            }
        }
    }
}
