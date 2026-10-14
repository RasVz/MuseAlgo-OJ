package top.everblue.oj.api.contest;

import java.time.LocalDateTime;

/** 比赛时间窗最小数据 — 提交访问校验/查重权限校验所需的比赛基础信息（只读投射）。 */
public record ContestSchedule(Long id, LocalDateTime startTime, LocalDateTime endTime,
                              String mode, Long authorId) {
}
