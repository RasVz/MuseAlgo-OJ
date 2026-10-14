package top.everblue.oj.api.contest;

/**
 * 班级比赛分页查询请求 — 教学端查看班级关联比赛列表的过滤条件载体。
 *
 * @param classId    班级 ID（必填）
 * @param page       页码（从 0 或 1 开始由实现方约定，默认 0）
 * @param size       每页条数
 * @param keyword    标题关键字（可空）
 * @param sortField  排序字段（可空，如 start_time）
 * @param sortOrder  排序方向 asc/desc（可空，默认 desc）
 */
public record ClassContestPageRequest(
        Long classId,
        int page,
        int size,
        String keyword,
        String sortField,
        String sortOrder
) {
}
