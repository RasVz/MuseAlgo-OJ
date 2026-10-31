package com.musealgo.oj.judge.infrastructure;

import org.springframework.stereotype.Service;
import com.musealgo.oj.api.submission.SubmissionQuery;
import com.musealgo.oj.judge.mapper.SubmissionMapper;

import java.util.List;
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

    /**
     * 某用户全部 AC 提交，按 (create_time, id) 升序。
     * <p>
     * 供 oj-analytics 在 T8 重判后重建标签 ELO：ELO 是按 AC 事件顺序累计的派生值，
     * 必须按历史顺序重放才能得到与旧链路一致的终态，故 SQL 的排序是契约的一部分
     * （见 {@code SubmissionMapper.selectAcceptedChronological}），适配器不再重排。
     * 空结果统一返回 {@link List#of()}，避免调用方对 null 做额外判空。
     * </p>
     */
    @Override
    public List<AcceptedSubmission> findAcceptedChronological(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<AcceptedSubmission> accepted = submissionMapper.selectAcceptedChronological(userId);
        return accepted == null || accepted.isEmpty() ? List.of() : accepted;
    }
}
