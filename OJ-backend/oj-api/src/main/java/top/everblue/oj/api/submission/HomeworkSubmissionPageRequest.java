package top.everblue.oj.api.submission;

import java.time.LocalDateTime;

/**
 * 作业提交分页查询请求 — 教师端查看作业提交列表的过滤条件载体。
 *
 * @param classId    班级 ID（必填）
 * @param homeworkId 作业（题单）ID（必填）
 * @param studentId  学生 ID（可空，按学生过滤）
 * @param problemId  题目 ID（可空，按题目过滤）
 * @param page       页码
 * @param size       每页条数
 * @param startTime  提交时间窗起点（可空）
 * @param endTime    提交时间窗终点（可空）
 */
public record HomeworkSubmissionPageRequest(
        Long classId,
        Long homeworkId,
        Long studentId,
        Long problemId,
        int page,
        int size,
        LocalDateTime startTime,
        LocalDateTime endTime
) {
}
