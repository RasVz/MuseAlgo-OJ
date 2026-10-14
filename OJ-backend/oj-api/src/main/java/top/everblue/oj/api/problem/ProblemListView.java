package top.everblue.oj.api.problem;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 题单视图 — 跨模块只读投射（班级作业/班级题单/收藏题单场景）。
 * <p>
 * 消费者不直接访问 {@code ProblemList} 实体，避免与 oj-problem 模块耦合。
 * </p>
 */
public record ProblemListView(
        Long id,
        String title,
        String description,
        Byte type,
        Long classId,
        Long creatorId,
        Integer problemCount,
        Integer favoriteCount,
        Integer privacy,
        LocalDateTime startTime,
        LocalDateTime endTime,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {

    /** 从一批视图里取指定 id 的视图；不存在返回 null。 */
    public static ProblemListView of(Collection<ProblemListView> views, Long id) {
        if (id == null || views == null) {
            return null;
        }
        return views.stream().filter(v -> id.equals(v.id())).findFirst().orElse(null);
    }
}
