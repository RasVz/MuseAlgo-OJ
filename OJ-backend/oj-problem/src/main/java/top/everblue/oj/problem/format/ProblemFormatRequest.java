package top.everblue.oj.problem.format;

import java.util.List;

/**
 * 格式化请求：AI 整理题面（可预览或直接保存）。
 *
 * @param problemIds 题目 ID 列表
 * @param ipMode     是否启用睿思星球 IP 改名（与格式化同一次 AI 调用完成，默认 false）
 */
public record ProblemFormatRequest(List<Long> problemIds, Boolean ipMode) {

    /** 兼容旧调用方（无 ipMode 字段） */
    public ProblemFormatRequest(List<Long> problemIds) {
        this(problemIds, false);
    }

    public boolean ipEnabled() {
        return Boolean.TRUE.equals(ipMode);
    }
}
