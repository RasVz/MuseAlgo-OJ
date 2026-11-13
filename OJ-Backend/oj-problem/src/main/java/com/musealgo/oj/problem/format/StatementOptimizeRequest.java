package com.musealgo.oj.problem.format;

/**
 * 基于某个版本发起优化（异步任务，单题）。
 *
 * @param baseVersionId 基线版本；为空时以当前题面为基线
 * @param ipMode        是否同时做睿思星球 IP 改名
 */
public record StatementOptimizeRequest(Long baseVersionId, Boolean ipMode) {

    public boolean ipEnabled() {
        return Boolean.TRUE.equals(ipMode);
    }
}
