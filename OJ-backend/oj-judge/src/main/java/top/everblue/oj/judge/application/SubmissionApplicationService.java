package top.everblue.oj.judge.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.contest.ContestSubmissionContextResolver;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.judge.entity.Submission;
import top.everblue.oj.judge.service.SubmissionService;

import java.util.List;

/** Coordinates the authenticated submission use case without exposing persistence entities to HTTP. */
@Service
public class SubmissionApplicationService {

    private final SubmissionService submissionService;
    private final ContestSubmissionContextResolver contestSubmissionContextResolver;

    public SubmissionApplicationService(SubmissionService submissionService,
                                        ContestSubmissionContextResolver contestSubmissionContextResolver) {
        this.submissionService = submissionService;
        this.contestSubmissionContextResolver = contestSubmissionContextResolver;
    }

    public SubmissionResultResponse submit(SubmitSolutionCommand command) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            throw new IllegalStateException("请先登录");
        }

        Submission submission = new Submission();
        submission.setUserId(userId);
        submission.setProblemId(command.getProblemId());
        if (command.getContestId() != null) {
            var context = contestSubmissionContextResolver.resolve(
                    command.getContestId(), command.getProblemId(), userId);
            submission.setContestId(context.contestId());
            submission.setVpAttemptId(context.vpAttemptId());
        }
        submission.setLanguage(command.getLanguage().trim());
        submission.setCode(command.getCode());

        return SubmissionResultResponse.from(submissionService.submitCode(submission));
    }

    /**
     * 用户最近 AC 的提交记录（个人主页展示用）。
     */
    public List<Submission> findRecentAccepted(Long userId, int limit) {
        return submissionService.list(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getUserId, userId)
                .eq(Submission::getStatus, (byte) 1)
                .orderByDesc(Submission::getCreateTime)
                .last("LIMIT " + Math.max(limit, 1)));
    }
}
