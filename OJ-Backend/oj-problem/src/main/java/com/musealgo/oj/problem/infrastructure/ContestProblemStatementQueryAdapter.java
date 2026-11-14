package com.musealgo.oj.problem.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.musealgo.oj.api.problem.ContestProblemStatementQuery;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.ProblemSample;
import com.musealgo.oj.problem.mapper.ProblemMapper;
import com.musealgo.oj.problem.mapper.ProblemSampleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@link ContestProblemStatementQuery} 的 oj-problem 侧实现：只读取白名单题面字段。 */
@Component
@RequiredArgsConstructor
public class ContestProblemStatementQueryAdapter implements ContestProblemStatementQuery {

    private final ProblemMapper problems;
    private final ProblemSampleMapper samples;

    @Override
    public Statement load(Long problemId) {
        Problem problem = problems.selectById(problemId);
        if (problem == null || Integer.valueOf(1).equals(problem.getIsDeleted())) {
            return null;
        }
        List<Sample> sampleViews = samples.selectList(new LambdaQueryWrapper<ProblemSample>()
                        .eq(ProblemSample::getProblemId, problemId)
                        .orderByAsc(ProblemSample::getSampleOrder))
                .stream()
                .map(sample -> new Sample(sample.getInput(), sample.getOutput()))
                .toList();
        return new Statement(problem.getId(), problem.getTitle(), problem.getContent(),
                problem.getInputDesc(), problem.getOutputDesc(), sampleViews,
                problem.getTimeLimit(), problem.getMemoryLimit());
    }
}
