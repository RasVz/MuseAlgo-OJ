package top.everblue.oj.judge.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmissionStatusTest {

    @Test
    void mapsJudge0StatusesToStableSubmissionStatuses() {
        assertEquals(SubmissionStatus.ACCEPTED, SubmissionStatus.fromJudge0(3));
        assertEquals(SubmissionStatus.WRONG_ANSWER, SubmissionStatus.fromJudge0(4));
        assertEquals(SubmissionStatus.TIME_LIMIT_EXCEEDED, SubmissionStatus.fromJudge0(5));
        assertEquals(SubmissionStatus.COMPILATION_ERROR, SubmissionStatus.fromJudge0(6));
        assertEquals(SubmissionStatus.RUNTIME_ERROR, SubmissionStatus.fromJudge0(7));
    }

    @Test
    void unknownJudge0StatusFallsBackToRuntimeError() {
        // Judge0 文档外的新状态码（如 8+）按运行时错误处理，不抛异常
        assertEquals(SubmissionStatus.RUNTIME_ERROR, SubmissionStatus.fromJudge0(99));
    }

    @Test
    void reverseLookupByPersistedCode() {
        assertEquals(SubmissionStatus.PENDING, SubmissionStatus.of((byte) 0));
        assertEquals(SubmissionStatus.ACCEPTED, SubmissionStatus.of((byte) 1));
        assertEquals(SubmissionStatus.WRONG_ANSWER, SubmissionStatus.of((byte) 2));
        assertEquals(SubmissionStatus.COMPILATION_ERROR, SubmissionStatus.of((byte) 3));
        assertEquals(SubmissionStatus.RUNTIME_ERROR, SubmissionStatus.of((byte) 4));
        assertEquals(SubmissionStatus.TIME_LIMIT_EXCEEDED, SubmissionStatus.of((byte) 5));
        assertNull(SubmissionStatus.of((byte) 42));
    }

    @Test
    void codesRoundTrip() {
        for (SubmissionStatus s : SubmissionStatus.values()) {
            assertEquals(s, SubmissionStatus.of(s.code()));
        }
    }

    @Test
    void semanticHelpersMatchPersistedContract() {
        assertTrue(SubmissionStatus.ACCEPTED.isAccepted());
        assertTrue(SubmissionStatus.ACCEPTED.isPassed());
        assertTrue(SubmissionStatus.WRONG_ANSWER.isWrongAnswer());
        assertTrue(SubmissionStatus.COMPILATION_ERROR.isCompileError());
        assertTrue(SubmissionStatus.RUNTIME_ERROR.isRuntimeError());
        assertTrue(SubmissionStatus.TIME_LIMIT_EXCEEDED.isTimeLimit());
        assertTrue(SubmissionStatus.PENDING.isPending());

        assertFalse(SubmissionStatus.PENDING.isPassed());
        assertFalse(SubmissionStatus.WRONG_ANSWER.isAccepted());
    }
}
