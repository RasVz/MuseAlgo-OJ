package com.musealgo.oj.problem.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.musealgo.oj.api.problem.ProblemAccessQuery;
import com.musealgo.oj.api.problem.ProblemTutoringQuery;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.problem.error.ProblemErrorCode;
import com.musealgo.oj.problem.entity.ProblemSample;
import com.musealgo.oj.problem.mapper.ProblemMapper;
import com.musealgo.oj.problem.mapper.ProblemSampleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProblemTutoringQueryAdapter implements ProblemTutoringQuery {
    private final ProblemAccessQuery access;
    private final ProblemMapper problems;
    private final ProblemSampleMapper samples;

    @Override public Context load(Long problemId) {
        // 必须在有 SecurityContext 的请求线程执行；不查询账号是否参赛。
        access.requireReadable(problemId);
        var p = problems.selectById(problemId);
        if (p == null || Integer.valueOf(1).equals(p.getIsDeleted()))
            throw new BusinessException(ProblemErrorCode.PROBLEM_NOT_FOUND);
        StringBuilder text = new StringBuilder();
        append(text, "题目描述", p.getContent());
        append(text, "输入描述", p.getInputDesc());
        append(text, "输出描述", p.getOutputDesc());
        append(text, "公开提示与数据范围", p.getHint());
        text.append("时间限制: ").append(p.getTimeLimit()).append(" ms；内存限制: ")
                .append(p.getMemoryLimit()).append(" MB\n");
        for (var sample : samples.selectList(new LambdaQueryWrapper<ProblemSample>()
                .eq(ProblemSample::getProblemId, problemId).orderByAsc(ProblemSample::getSampleOrder))) {
            append(text, "公开样例输入", sample.getInput());
            append(text, "公开样例输出", sample.getOutput());
        }
        return new Context(problemId, p.getTitle(), text.toString());
    }
    private static void append(StringBuilder target, String label, String content) {
        if (content != null && !content.isBlank()) target.append("【").append(label).append("】\n").append(content).append('\n');
    }
}
