package com.musealgo.oj.api.problem;

import java.util.List;

/**
 * 由题面草稿落库所需的字段。
 * <p>
 * 刻意不传 {@code Problem} 实体：草稿侧只应提供人工可编辑的题面字段，
 * 统计计数、归属 ID、创建时间等由题目域自行决定，避免 AI 流程写入不可控字段。
 * </p>
 * <p>
 * 也不提供 {@code isPublic}：AI 生成的题面必然缺少判题用例，只能先落成私有草稿，
 * 公开与否由教师补齐用例后在题库里决定，不给调用方这个开关。
 * </p>
 *
 * @param tags    标签名（不含括号与引号），实现方按题库既有约定序列化
 * @param samples 公开样例，只含输入与输出
 */
public record DraftProblemCommand(
        String title,
        String content,
        String inputDesc,
        String outputDesc,
        String hint,
        Integer difficulty,
        Integer timeLimit,
        Integer memoryLimit,
        List<String> tags,
        List<DraftProblemSample> samples,
        String source) {
}
