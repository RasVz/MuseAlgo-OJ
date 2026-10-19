package top.everblue.oj.judge.infrastructure;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.submission.ContestSubmission;
import top.everblue.oj.api.submission.ContestSubmissionPageRequest;
import top.everblue.oj.api.submission.ContestSubmissionQuery;
import top.everblue.oj.api.submission.PageResult;
import top.everblue.oj.judge.mapper.SubmissionMapper;

import java.util.List;
import java.util.Optional;

/** Transitional adapter: hides the legacy submission mapper behind oj-api. */
@Service
public class SubmissionHistoryQueryAdapter implements ContestSubmissionQuery {

    private final SubmissionMapper submissionMapper;

    public SubmissionHistoryQueryAdapter(SubmissionMapper submissionMapper) {
        this.submissionMapper = submissionMapper;
    }

    @Override
    public boolean hasPending(Long contestId) {
        return submissionMapper.countPendingContest(contestId) > 0;
    }

    @Override
    public List<ContestSubmission> findByContestId(Long contestId) {
        return submissionMapper.selectAllByContestIdOrderByCreateTimeAsc(contestId).stream()
                .map(submission -> new ContestSubmission(
                        submission.getId(),
                        submission.getContestId(),
                        submission.getVpAttemptId(),
                        submission.getUserId(),
                        submission.getProblemId(),
                        submission.getStatus(),
                        submission.getLanguage(),
                        submission.getCreateTime(),
                        submission.getScore()
                ))
                .toList();
    }

    @Override
    public PageResult<ContestSubmission> findPage(ContestSubmissionPageRequest request) {
        Page<top.everblue.oj.judge.entity.Submission> page = submissionMapper.selectContestSubmissionPage(
                new Page<>(request.page(), request.size()),
                request.contestId(),
                request.mineOnly(),
                request.currentUserId(),
                request.problemId(),
                request.language(),
                request.status()
        );
        List<ContestSubmission> records = page.getRecords().stream()
                .map(submission -> new ContestSubmission(
                        submission.getId(),
                        submission.getContestId(),
                        submission.getVpAttemptId(),
                        submission.getUserId(),
                        submission.getProblemId(),
                        submission.getStatus(),
                        submission.getLanguage(),
                        submission.getCreateTime(),
                        submission.getScore()
                ))
                .toList();
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    @Override
    public Optional<ContestSubmission> findById(Long submissionId) {
        var submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            return Optional.empty();
        }
        return Optional.of(new ContestSubmission(
                submission.getId(),
                submission.getContestId(),
                submission.getVpAttemptId(),
                submission.getUserId(),
                submission.getProblemId(),
                submission.getStatus(),
                submission.getLanguage(),
                submission.getCreateTime(),
                submission.getScore()
        ));
    }
}
