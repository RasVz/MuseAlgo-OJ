package top.everblue.oj.judge.domain;

import java.util.Arrays;

/**
 * Stable mapping between persisted submission states and Judge0 result ids.
 * <p>
 * Persisted codes (0–5) are treated as a contract; do not renumber. The
 * semantic helpers below let analytics and reporting code avoid magic numbers
 * such as {@code status == 1}.
 */
public enum SubmissionStatus {
    PENDING((byte) 0),
    ACCEPTED((byte) 1),
    WRONG_ANSWER((byte) 2),
    COMPILATION_ERROR((byte) 3),
    RUNTIME_ERROR((byte) 4),
    TIME_LIMIT_EXCEEDED((byte) 5);

    private final byte code;

    SubmissionStatus(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    /** Reverse-lookup by the persisted code; {@code null} if unknown. */
    public static SubmissionStatus of(byte code) {
        return Arrays.stream(values())
                .filter(s -> s.code == code)
                .findFirst()
                .orElse(null);
    }

    public static SubmissionStatus fromJudge0(int judge0Status) {
        return switch (judge0Status) {
            case 3 -> ACCEPTED;
            case 4 -> WRONG_ANSWER;
            case 5 -> TIME_LIMIT_EXCEEDED;
            case 6 -> COMPILATION_ERROR;
            default -> RUNTIME_ERROR;
        };
    }

    // --- semantic helpers: use these instead of comparing raw codes ---

    /** AC; the problem is considered passed for profiling/ranking. */
    public boolean isAccepted() { return this == ACCEPTED; }

    public boolean isWrongAnswer() { return this == WRONG_ANSWER; }

    public boolean isCompileError() { return this == COMPILATION_ERROR; }

    public boolean isRuntimeError() { return this == RUNTIME_ERROR; }

    public boolean isTimeLimit() { return this == TIME_LIMIT_EXCEEDED; }

    public boolean isPending() { return this == PENDING; }

    /** Whether the submission should be counted as "passed" in analytics. Currently equals AC. */
    public boolean isPassed() { return this == ACCEPTED; }
}
