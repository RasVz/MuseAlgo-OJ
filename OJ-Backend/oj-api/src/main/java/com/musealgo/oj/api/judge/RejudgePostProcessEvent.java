package com.musealgo.oj.api.judge;

import java.time.LocalDateTime;

/**
 * 重判完成后的派生数据重建事件（T8）。
 * <p>
 * 由 oj-judge 在判题结果与 rejudge_item 同事务提交后发布；监听方在各数据归属模块内
 * 重建标签 ELO、把画像快照标记为过期等。放在 oj-api 是为了让 oj-system / oj-analytics
 * 不依赖 oj-judge 实现，同时不引入 MQ 必需依赖。
 * </p>
 * <p>
 * 语义是“已经成功重判并写入新结果”，不是“重判请求已提交”。副作用失败不回滚重判结果，
 * 由 T8-2 的重试/对账兜底。
 * </p>
 *
 * @param operationId   重判操作 ID
 * @param itemId        重判条目 ID
 * @param submissionId  提交 ID
 * @param userId        提交人
 * @param problemId     题目 ID
 * @param submissionTime 原提交时间（用于定位受影响的画像周期）
 * @param vpAttemptId     VP 会话 ID（非 VP 为 null；T8-2b 用它在判题后重算 VP 成绩）
 */
public record RejudgePostProcessEvent(
        Long operationId,
        Long itemId,
        Long submissionId,
        Long userId,
        Long problemId,
        LocalDateTime submissionTime,
        Long vpAttemptId
) {
}
