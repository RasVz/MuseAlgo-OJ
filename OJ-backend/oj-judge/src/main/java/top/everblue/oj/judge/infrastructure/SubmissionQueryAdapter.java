package top.everblue.oj.judge.infrastructure;

import org.springframework.stereotype.Service;
import top.everblue.oj.api.submission.SubmissionQuery;
import top.everblue.oj.judge.mapper.SubmissionMapper;

import java.util.Optional;

/** {@link SubmissionQuery} 的 oj-judge 实现。 */
@Service
public class SubmissionQueryAdapter implements SubmissionQuery {

    private final SubmissionMapper submissionMapper;

    public SubmissionQueryAdapter(SubmissionMapper submissionMapper) {
        this.submissionMapper = submissionMapper;
    }

    @Override
    public Optional<SubmissionContext> findById(Long submissionId) {
        if (submissionId == null) {
            return Optional.empty();
        }
        var submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            return Optional.empty();
        }
        return Optional.of(new SubmissionContext(
                submission.getId(),
                submission.getUserId(),
                submission.getProblemId(),
                submission.getContestId(),
                submission.getStatus(),
                submission.getScore()
        ));
    }
}
