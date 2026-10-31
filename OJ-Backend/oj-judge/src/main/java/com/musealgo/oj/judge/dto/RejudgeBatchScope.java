package com.musealgo.oj.judge.dto;

import java.time.LocalDateTime;

/**
 * 批量重判范围参数（T8-2a）。
 * <p>scopeType：PROBLEM / FAILED / ENDED_CONTEST / TIME_RANGE；不同范围要求对应字段：</p>
 * <ul>
 *   <li>PROBLEM：problemId 必填；</li>
 *   <li>FAILED：可叠加 problemId/contestId/from/to 过滤；</li>
 *   <li>ENDED_CONTEST：contestId 必填，且比赛必须已结束、未固化；</li>
 *   <li>TIME_RANGE：from/to 必填，半开区间 [from,to)。</li>
 * </ul>
 */
public record RejudgeBatchScope(
        String scopeType,
        Long problemId,
        Long contestId,
        LocalDateTime from,
        LocalDateTime to
) {
}
