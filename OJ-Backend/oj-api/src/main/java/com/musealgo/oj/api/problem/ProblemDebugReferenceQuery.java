package com.musealgo.oj.api.problem;

/** 仅供运行侧使用；参考程序禁止发给学生或模型，也不读取隐藏评测用例。 */
public interface ProblemDebugReferenceQuery {
    record Reference(String officialExpected, String verifiedProgram) { }
    Reference find(Long problemId, String input);
}
