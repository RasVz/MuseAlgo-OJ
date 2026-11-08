package com.musealgo.oj.problem.application;

/**
 * 判题用例缓存失效契约。
 * <p>
 * 判题侧读取用例经过 {@link com.musealgo.oj.problem.infrastructure.ProblemQueryAdapter}
 * 的进程内缓存（默认 TTL 60s）。用例写入完成后必须显式失效，否则教师改完用例后
 * 一段时间内的提交仍会按旧数据判题。
 * </p>
 * <p>
 * 失效范围仅限当前 JVM。多实例部署时其他实例仍依赖 TTL 兜底，调用方不得据此声称
 * 多实例已实时一致。
 * </p>
 */
public interface ProblemCaseCacheInvalidator {

    /** 失效指定题目的判题用例缓存（含题目资源限制缓存之外的用例条目）。 */
    void evictProblemCases(Long problemId);
}
