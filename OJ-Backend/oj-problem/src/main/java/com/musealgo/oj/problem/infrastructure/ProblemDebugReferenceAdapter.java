package com.musealgo.oj.problem.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.musealgo.oj.api.problem.ProblemDebugReferenceQuery;
import com.musealgo.oj.problem.entity.ProblemSample;
import com.musealgo.oj.problem.mapper.ProblemMapper;
import com.musealgo.oj.problem.mapper.ProblemSampleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ProblemDebugReferenceAdapter implements ProblemDebugReferenceQuery {
    private final ProblemMapper problems;
    private final ProblemSampleMapper samples;
    @Override public Reference find(Long id, String input) {
        var problem = problems.selectById(id);
        if (problem == null || Integer.valueOf(1).equals(problem.getIsDeleted())) return new Reference(null, null);
        var expected = samples.selectList(new LambdaQueryWrapper<ProblemSample>()
                .eq(ProblemSample::getProblemId, id).orderByAsc(ProblemSample::getSampleOrder)).stream()
                .filter(sample -> Objects.equals(sample.getInput(), input)).map(ProblemSample::getOutput)
                .filter(Objects::nonNull).findFirst().orElse(null);
        String program = Integer.valueOf(1).equals(problem.getStdTested()) ? problem.getStdAnswer() : null;
        return new Reference(expected, program);
    }
}
