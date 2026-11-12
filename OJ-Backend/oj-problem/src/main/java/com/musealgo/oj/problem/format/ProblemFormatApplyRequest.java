package com.musealgo.oj.problem.format;

import java.util.List;

/**
 * 应用整理结果。
 *
 * @param problemId 题目 ID
 * @param baseHash  预览时返回的原文哈希；非空时必须与当前库内内容一致，否则判定为陈旧结果
 * @param fields    采纳的字段白名单：title / content / inputDesc / outputDesc / hint / limits / samples；
 *                  为空表示全部采纳（兼容旧调用方）
 * @param formatted 整理后的内容（可被前端编辑过）
 */
public record ProblemFormatApplyRequest(
        Long problemId,
        String baseHash,
        List<String> fields,
        ProblemFormattedContent formatted
) {
}
