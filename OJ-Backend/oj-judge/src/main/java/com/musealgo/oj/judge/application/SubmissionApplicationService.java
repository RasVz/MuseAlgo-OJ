package com.musealgo.oj.judge.application;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.common.exception.CommonErrorCode;
import com.musealgo.oj.judge.error.JudgeErrorCode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import com.musealgo.oj.api.contest.ContestSubmissionContextResolver;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.judge.entity.Submission;
import com.musealgo.oj.judge.service.SubmissionService;

import java.util.List;

/** Coordinates the authenticated submission use case without exposing persistence entities to HTTP. */
@Service
public class SubmissionApplicationService {

    private final SubmissionService submissionService;
    private final com.musealgo.oj.api.problem.ProblemAccessQuery problemAccess;
    private final ContestSubmissionContextResolver contestSubmissionContextResolver;

    public SubmissionApplicationService(com.musealgo.oj.api.problem.ProblemAccessQuery problemAccess, SubmissionService submissionService,
                                        ContestSubmissionContextResolver contestSubmissionContextResolver) {
        this.problemAccess = problemAccess;
        this.submissionService = submissionService;
        this.contestSubmissionContextResolver = contestSubmissionContextResolver;
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public SubmissionResultResponse submit(SubmitSolutionCommand command) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            throw new BusinessException(CommonErrorCode.AUTH_REQUIRED, "请先登录");
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
        if (command.getContestId() == null) problemAccess.requirePracticeSubmission(command.getProblemId());
        String language = command.getLanguage() == null ? "" : command.getLanguage().trim();
        if (!com.musealgo.oj.judge.JudgeService.isSupportedLanguage(language)) {
            // 不受支持的语言若被受理，会被判题网关回退成 Python 执行，成绩语义不成立。
            throw new BusinessException(JudgeErrorCode.LANGUAGE_UNSUPPORTED, "当前仅支持 C++ / Java / Python 提交");
        }
        submission.setLanguage(language);
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
