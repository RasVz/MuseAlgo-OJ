package com.musealgo.oj.judge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musealgo.oj.judge.entity.Submission;

/**
 * 重判结果快照编解码（T8）。
 * <p>
 * old_result_json / new_result_json 只保存“结果字段”，不含 code/error 大文本之外的用户敏感内容；
 * 重判失败时用该快照恢复原结果，避免一条失败的重判把用户原本有效的成绩清空。
 * </p>
 */
public final class RejudgeResultCodec {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RejudgeResultCodec() {
    }

    /** 快照结构：与 submission 的结果列一一对应。 */
    public record Snapshot(Byte status, Integer score, Integer runTime, Integer runMemory,
                           String errorMessage, String judgePhase) {
    }

    public static String toJson(Submission submission) {
        try {
            return JSON.writeValueAsString(new Snapshot(
                    submission.getStatus(), submission.getScore(), submission.getRunTime(),
                    submission.getRunMemory(), submission.getErrorMessage(), submission.getJudgePhase()));
        } catch (Exception e) {
            throw new IllegalStateException("重判结果快照序列化失败", e);
        }
    }

    /** 把快照字段恢复到 submission 实体（不修改 id/userId/problemId 等身份字段）。 */
    public static void restore(Submission submission, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            Snapshot snapshot = JSON.readValue(json, Snapshot.class);
            submission.setStatus(snapshot.status());
            submission.setScore(snapshot.score());
            submission.setRunTime(snapshot.runTime());
            submission.setRunMemory(snapshot.runMemory());
            submission.setErrorMessage(snapshot.errorMessage());
            submission.setJudgePhase(snapshot.judgePhase());
        } catch (Exception e) {
            throw new IllegalStateException("重判结果快照反序列化失败", e);
        }
    }
}
