package com.musealgo.oj.problem.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.musealgo.oj.api.problem.ExamAttemptAccessQuery;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.problem.entity.ExamAttempt;
import com.musealgo.oj.problem.entity.ExamPaperProblem;
import com.musealgo.oj.problem.error.ProblemErrorCode;
import com.musealgo.oj.problem.mapper.ExamAttemptMapper;
import com.musealgo.oj.problem.mapper.ExamPaperProblemMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * {@link ExamAttemptAccessQuery} 的 oj-problem 实现。
 *
 * <p>提交入口只信这个出口的判定：会话必须存在、属于提交人、仍在进行中且未超时，
 * 并且题目确实属于该试卷。任何一项不满足都直接拒绝，避免练习提交混入试卷成绩。</p>
 */
@Service
@RequiredArgsConstructor
public class ExamAttemptAccessAdapter implements ExamAttemptAccessQuery {

    private final ExamAttemptMapper examAttemptMapper;
    private final ExamPaperProblemMapper examPaperProblemMapper;

    @Override
    public AttemptAccess requireActiveAttempt(Long attemptId, Long problemId, Long userId) {
        ExamAttempt attempt = examAttemptMapper.selectById(attemptId);
        if (attempt == null) {
            throw new BusinessException(ProblemErrorCode.EXAM_ATTEMPT_NOT_ACTIVE, "作答会话不存在");
        }
        if (!userId.equals(attempt.getUserId())) {
            throw new ForbiddenException("无权在该作答会话提交");
        }
        if (!ExamAttempt.STATUS_RUNNING.equals(attempt.getStatus())) {
            throw new BusinessException(ProblemErrorCode.EXAM_ATTEMPT_NOT_ACTIVE, "本次作答已交卷");
        }
        if (attempt.getEndTime() == null || !attempt.getEndTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ProblemErrorCode.EXAM_ATTEMPT_NOT_ACTIVE, "作答时间已结束");
        }
        Long count = examPaperProblemMapper.selectCount(new LambdaQueryWrapper<ExamPaperProblem>()
                .eq(ExamPaperProblem::getPaperId, attempt.getPaperId())
                .eq(ExamPaperProblem::getProblemId, problemId));
        if (count == null || count == 0) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "题目不属于当前试卷");
        }
        return new AttemptAccess(
                attempt.getId(), attempt.getPaperId(), attempt.getUserId(),
                attempt.getStartTime(), attempt.getEndTime());
    }
}
