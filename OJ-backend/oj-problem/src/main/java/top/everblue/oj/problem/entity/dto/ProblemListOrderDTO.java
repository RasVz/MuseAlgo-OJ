package top.everblue.oj.problem.entity.dto;

import lombok.Data;
import java.util.List;

/**
 * 题单内题目重排序的入参。
 * <p>
 * 采用<b>全量顺序</b>而非"移动某题到某位"的增量形式：前端拖拽结束时
 * 直接给出最终顺序，服务端整体重写即可，不必实现位置偏移与冲突处理。
 * </p>
 */
@Data
public class ProblemListOrderDTO {
    /**
     * 题单 ID
     */
    private Long listId;

    /**
     * 排好序的题目 ID 列表
     * 前端传过来是 [1001, 1005, 1002...]，下标就是顺序
     */
    private List<Long> problemIds;
}
