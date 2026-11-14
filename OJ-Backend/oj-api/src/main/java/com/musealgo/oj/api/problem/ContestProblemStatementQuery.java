package com.musealgo.oj.api.problem;

import java.util.List;

/**
 * 比赛参赛卷的白名单题面查询。
 * <p>只返回题面、输入输出描述、样例与限制；难度、标签、通过率、提示与标准答案一律不进入返回结构。
 * 参赛资格与比赛时间窗由调用方（oj-contest）校验，本接口不做权限判断。</p>
 */
public interface ContestProblemStatementQuery {

    Statement load(Long problemId);

    record Statement(
            Long problemId,
            String title,
            String content,
            String inputDesc,
            String outputDesc,
            List<Sample> samples,
            Integer timeLimit,
            Integer memoryLimit
    ) { }

    record Sample(String input, String output) { }
}
