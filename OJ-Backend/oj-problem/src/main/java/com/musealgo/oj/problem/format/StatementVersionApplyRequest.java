package com.musealgo.oj.problem.format;

import java.util.List;

/**
 * 应用候选版本：只把选中字段写进新版本，其余字段沿用基线版本。
 *
 * @param fields    采纳字段：title/content/inputDesc/outputDesc/hint/timeLimit/memoryLimit/samples；
 *                  为空表示全部采纳
 * @param formatted 候选版本内容（可被前端编辑过）
 */
public record StatementVersionApplyRequest(List<String> fields, ProblemFormattedContent formatted) {
}
