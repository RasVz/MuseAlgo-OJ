package top.everblue.oj.judge.infrastructure;

import org.springframework.stereotype.Service;
import top.everblue.oj.api.submission.SubmissionCodeQuery;
import top.everblue.oj.judge.mapper.SubmissionMapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 提交代码只读契约实现（oj-analytics 查重引擎消费）。
 * <p>
 * 数据归属 oj-judge（submission 表），SQL 走 {@link SubmissionMapper}；
 * 班级过滤在 SQL 内经 class_student JOIN 完成（与学情聚合查询同模式）。
 * </p>
 */
@Service
public class SubmissionCodeQueryAdapter implements SubmissionCodeQuery {

    private final SubmissionMapper submissionMapper;

    public SubmissionCodeQueryAdapter(SubmissionMapper submissionMapper) {
        this.submissionMapper = submissionMapper;
    }

    @Override
    public List<CodePairSource> findByClassAndProblem(Long classId, Long problemId, LocalDateTime since) {
        return submissionMapper.selectClassProblemCodes(classId, problemId, since).stream()
                .map(SubmissionCodeQueryAdapter::toSource)
                .toList();
    }

    @Override
    public List<CodePairSource> findBySubmissionIds(Collection<Long> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            return List.of();
        }
        return submissionMapper.selectCodesBySubmissionIds(submissionIds).stream()
                .map(SubmissionCodeQueryAdapter::toSource)
                .toList();
    }

    @Override
    public List<CodePairSource> findByContestAndProblem(Long contestId, Long problemId, LocalDateTime since) {
        return submissionMapper.selectContestProblemCodes(contestId, problemId, since).stream()
                .map(SubmissionCodeQueryAdapter::toSource)
                .toList();
    }

    @Override
    public List<Long> findContestProblemIds(Long contestId) {
        return submissionMapper.selectContestProblemIds(contestId);
    }

    private static CodePairSource toSource(java.util.Map<String, Object> row) {
        return new CodePairSource(
                ((Number) row.get("submissionId")).longValue(),
                ((Number) row.get("userId")).longValue(),
                ((Number) row.get("problemId")).longValue(),
                (String) row.get("code"),
                String.valueOf(row.get("status")),
                (LocalDateTime) row.get("createTime"));
    }
}
