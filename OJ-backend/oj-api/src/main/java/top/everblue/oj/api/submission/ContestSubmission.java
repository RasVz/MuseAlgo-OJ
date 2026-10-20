package top.everblue.oj.api.submission;

import java.time.LocalDateTime;

/** 比赛提交只读视图 — 提供给提交历史的跨模块消费者，不含源码等敏感字段。 */
public record ContestSubmission(
        Long id,
        Long contestId,
        Long vpAttemptId,
        Long userId,
        Long problemId,
        Byte status,
        String language,
        LocalDateTime submittedAt,
        Integer score
) {
    // Submission 状态码常量（与 oj-judge SubmissionStatus 枚举保持同步）
    public static final byte ST_ACCEPTED = 1;
    public static final byte ST_WRONG_ANSWER = 2;
    public static final byte ST_COMPILATION_ERROR = 3;
    public static final byte ST_RUNTIME_ERROR = 4;
    public static final byte ST_TIME_LIMIT_EXCEEDED = 5;

    /** 将 status byte 映射为 可读状态字符串。 */
    public static String statusLabel(Byte status) {
        if (status == null) return "UNKNOWN";
        return switch (status) {
            case ST_ACCEPTED -> "AC";
            case ST_WRONG_ANSWER -> "WA";
            case ST_COMPILATION_ERROR -> "CE";
            case ST_RUNTIME_ERROR -> "RE";
            case ST_TIME_LIMIT_EXCEEDED -> "TLE";
            default -> "UNKNOWN";
        };
    }
}
