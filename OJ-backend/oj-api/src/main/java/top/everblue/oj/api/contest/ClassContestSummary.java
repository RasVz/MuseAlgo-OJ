package top.everblue.oj.api.contest;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

/** 班级比赛摘要视图 — 跨模块只读投射，供教学端展示而不暴露比赛实体。 */
public record ClassContestSummary(
        Long id,
        String title,
        String description,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
        Integer duration,
        String type,
        Long authorId,
        String authorName,
        Integer participantCount,
        Integer visible,
        Long classId
) {
}
