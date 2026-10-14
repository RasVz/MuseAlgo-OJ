package top.everblue.oj.problem.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import top.everblue.oj.problem.entity.ProblemSample;
import top.everblue.oj.problem.format.FormatSample;
import top.everblue.oj.problem.service.ProblemSampleService;

import java.util.ArrayList;
import java.util.List;

/**
 * 题目公开样例管理：查询与整体替换（CRUD 域与格式化域共用）。
 */
@Service
public class ProblemSampleApplicationService {

    private final ProblemSampleService problemSampleService;

    public ProblemSampleApplicationService(ProblemSampleService problemSampleService) {
        this.problemSampleService = problemSampleService;
    }

    /** 按 sample_order 升序读取某道题的全部公开样例。 */
    public List<ProblemSample> getSamples(Long problemId) {
        return problemSampleService.list(new LambdaQueryWrapper<ProblemSample>()
                .eq(ProblemSample::getProblemId, problemId)
                .orderByAsc(ProblemSample::getSampleOrder));
    }

    /** 整体替换某道题的公开样例（按列表顺序重新编号）；超过 50 组拒绝。 */
    public void replaceSamples(Long problemId, List<ProblemSample> samples) {
        problemSampleService.remove(new LambdaQueryWrapper<ProblemSample>()
                .eq(ProblemSample::getProblemId, problemId));
        if (samples == null || samples.isEmpty()) return;
        if (samples.size() > 50) throw new IllegalArgumentException("样例数量超过 50 组，请人工整理");
        List<ProblemSample> rows = new ArrayList<>(samples.size());
        for (int index = 0; index < samples.size(); index++) {
            ProblemSample sample = samples.get(index);
            if (sample == null) continue;
            ProblemSample row = new ProblemSample();
            row.setProblemId(problemId);
            row.setSampleOrder(index + 1);
            row.setInput(nullToEmpty(sample.getInput()));
            row.setOutput(nullToEmpty(sample.getOutput()));
            rows.add(row);
        }
        if (!rows.isEmpty()) problemSampleService.saveBatch(rows);
    }

    /** 格式化结果落库用：把 AI 返回的 FormatSample 列表整体替换为公开样例。 */
    public void replaceFormatSamples(Long problemId, List<FormatSample> samples) {
        if (samples == null || samples.isEmpty()) {
            problemSampleService.remove(new LambdaQueryWrapper<ProblemSample>()
                    .eq(ProblemSample::getProblemId, problemId));
            return;
        }
        if (samples.size() > 50) throw new IllegalArgumentException("样例数量超过 50 组，请人工整理");
        List<ProblemSample> rows = new ArrayList<>(samples.size());
        for (int index = 0; index < samples.size(); index++) {
            FormatSample sample = samples.get(index);
            if (sample == null) continue;
            ProblemSample row = new ProblemSample();
            row.setProblemId(problemId);
            row.setSampleOrder(index + 1);
            row.setInput(nullToEmpty(sample.input()));
            row.setOutput(nullToEmpty(sample.output()));
            rows.add(row);
        }
        if (!rows.isEmpty()) problemSampleService.saveBatch(rows);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
