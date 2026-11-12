package com.musealgo.oj.problem.format;

import java.util.List;

/**
 * 应用结果：返回真正写入的字段与服务端最新哈希。
 * <p>
 * 前端用 {@code baseHash} 更新本地基线，避免紧接着的第二次应用被误判为陈旧请求。
 * </p>
 */
public record ProblemFormatApplyResult(Long problemId, List<String> appliedFields, String baseHash) {
}
