package com.musealgo.oj.contest.error;

import com.musealgo.oj.common.exception.ErrorCode;

/** Stable public reasons owned by this module. */
public enum ContestErrorCode implements ErrorCode {
    SETTLEMENT_REPLAY_BLOCKED(409, "当前状态不允许执行重放，请重新预览"),
    CONTEST_ALREADY_REGISTERED(409, "您已经报名过该比赛啦！"),
    CONTEST_ATTEMPT_NOT_ACTIVE(409, "当前不在比赛或虚拟赛作答时间内"),
    CONTEST_EARLIER_SETTLEMENT_PENDING(409, "更早结束的比赛尚未结算，等待后重试"),
    CONTEST_FINALIZED(409, "已固化比赛不能更改分类/等级/报名审核规则"),
    CONTEST_INPUT_INVALID(400, "请求参数不合法"),
    CONTEST_JUDGING_PENDING(409, "仍有提交正在判题，等待后重试"),
    CONTEST_LEVEL_NOT_FOUND(404, "等级不存在"),
    CONTEST_MODE_LOCKED(409, "已创建比赛不能更改赛制"),
    CONTEST_NOT_ENDED(409, "比赛尚未结束"),
    CONTEST_NOT_FOUND(404, "比赛不存在"),
    CONTEST_PROBLEM_ALREADY_ADDED(409, "该题目已在竞赛中，请勿重复添加"),
    CONTEST_PROBLEM_NOT_FOUND(404, "题目不属于该比赛"),
    CONTEST_REGISTRATION_NOT_FOUND(404, "报名记录不存在"),
    CONTEST_REGISTRATION_NOT_PENDING(409, "仅待审核的报名可以审核"),
    CONTEST_REGISTRATION_PENDING(409, "报名审核中，暂不能提交"),
    CONTEST_REGISTRATION_REJECTED(403, "报名未通过审核，不能提交"),
    CONTEST_REGISTRATION_REQUIRED(403, "请先报名并通过审核后再提交"),
    CONTEST_VP_NOT_AVAILABLE(409, "比赛进行中或未开始，暂不能虚拟参赛"),
    SETTLEMENT_REPLAY_DISABLED(409, "链式重放维护开关未开启"),
    SETTLEMENT_REPLAY_NOT_FOUND(404, "重放记录不存在"),
    SETTLEMENT_REPLAY_NOT_RESTORABLE(409, "仅成功且未恢复的重放可恢复"),
    SETTLEMENT_REPLAY_PREVIEW_CHANGED(409, "预览已变化或确认串不匹配，请重新预览");

    private final int status;
    private final String message;
    ContestErrorCode(int status, String message) { this.status = status; this.message = message; }
    public String key() { return name(); }
    public int status() { return status; }
    public String message() { return message; }
}
