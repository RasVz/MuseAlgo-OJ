package top.everblue.oj.judge.infrastructure;

import org.springframework.stereotype.Service;
import top.everblue.oj.api.submission.SubmissionReviewQuery;
import top.everblue.oj.judge.entity.SubmissionCode;
import top.everblue.oj.judge.mapper.SubmissionCodeMapper;
import top.everblue.oj.judge.mapper.SubmissionMapper;

import java.util.List;
import java.util.Optional;

/** AI 代码复盘契约实现：submission + submission_case_result 状态统计。 */
@Service
public class SubmissionReviewQueryAdapter implements SubmissionReviewQuery {

    private final SubmissionMapper submissionMapper;

    private final SubmissionCodeMapper submissionCodeMapper;

    public SubmissionReviewQueryAdapter(SubmissionMapper submissionMapper,
                                        SubmissionCodeMapper submissionCodeMapper) {
        this.submissionMapper = submissionMapper;
        this.submissionCodeMapper = submissionCodeMapper;
    }

    @Override
    public Optional<SubmissionReviewData> findById(Long submissionId) {
        var submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            return Optional.empty();
        }
        // V50 拆表：AI 复盘需要源码，从 submission_code 补载
        SubmissionCode codeRecord = submissionCodeMapper.selectById(submissionId);
        submission.setCode(codeRecord == null ? null : codeRecord.getCode());
        List<CaseStatusCount> caseStats = submissionMapper.selectCaseStatusCounts(submissionId).stream()
                .map(row -> new CaseStatusCount(
                        ((Number) row.get("status")).intValue(),
                        ((Number) row.get("cnt")).longValue()))
                .toList();
        return Optional.of(new SubmissionReviewData(
                submission.getId(),
                submission.getUserId(),
                submission.getProblemId(),
                submission.getStatus(),
                submission.getLanguage(),
                submission.getCode(),
                submission.getScore(),
                submission.getRunTime(),
                submission.getRunMemory(),
                submission.getErrorMessage(),
                submission.getContestId(),
                caseStats
        ));
    }
}
