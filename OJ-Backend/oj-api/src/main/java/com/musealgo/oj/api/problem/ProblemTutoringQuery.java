package com.musealgo.oj.api.problem;

/** 在请求线程校验当前主体的题目可读权限，并读取公开教学材料。 */
public interface ProblemTutoringQuery {
    Context load(Long problemId);
    /** 不含教师标准答案、私有评测数据。 */
    record Context(Long problemId, String title, String statement) { }
}
