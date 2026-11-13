package com.musealgo.oj.problem.application;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.problem.error.ProblemErrorCode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.api.submission.SubmissionAttemptQuery;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.problem.domain.ObjectiveJudge;
import com.musealgo.oj.problem.entity.ExamAnswer;
import com.musealgo.oj.problem.entity.ExamAttempt;
import com.musealgo.oj.problem.entity.ExamPaper;
import com.musealgo.oj.problem.entity.ExamPaperProblem;
import com.musealgo.oj.problem.entity.ExamQuestion;
import com.musealgo.oj.problem.entity.dto.ExamAnswerSubmitDTO;
import com.musealgo.oj.problem.entity.vo.ExamAttemptVO;
import com.musealgo.oj.problem.entity.vo.ExamPaperVO;
import com.musealgo.oj.problem.mapper.ExamAnswerMapper;
import com.musealgo.oj.problem.mapper.ExamAttemptMapper;
import com.musealgo.oj.problem.mapper.ExamPaperMapper;
import com.musealgo.oj.problem.mapper.ExamPaperProblemMapper;
import com.musealgo.oj.problem.mapper.ExamQuestionMapper;
import com.musealgo.oj.problem.service.impl.AttemptExpiredException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 训练试卷作答会话域：开始作答 / 提交客观题 / 交卷（主动与超时自动）/ 过期批量收卷。
 * <p>
 * 从 {@link com.musealgo.oj.problem.service.impl.ExamPaperServiceImpl} 拆出
 * （2026-08-15 大服务类拆分）；接口方法仍由主实现暴露，事务注解迁移至本类。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamAttemptApplicationService {

    private final ExamPaperMapper examPaperMapper;
    private final ExamPaperProblemMapper examPaperProblemMapper;
    private final ExamQuestionMapper examQuestionMapper;
    private final ExamAnswerMapper examAnswerMapper;
    private final ExamAttemptMapper examAttemptMapper;
    private final SubmissionAttemptQuery submissionAttemptQuery;

    /**
     * 提交客观题作答（即时判分）；作答超时先自动交卷再抛 {@link AttemptExpiredException}。
     */
    @Transactional(rollbackFor = Exception.class, noRollbackFor = AttemptExpiredException.class)
    public List<ExamPaperVO.ExamQuestionResultVO> submitAnswers(
            Long paperId, Long userId, ExamAnswerSubmitDTO submit) {
        ExamPaper paper = requireExistingPaper(paperId);
        ExamAttempt running = findRunningAttempt(paperId, userId);
        requirePublishedOrAdminOrOwnAttempt(paper, running);
        if (running == null) {
            throw new BusinessException(ProblemErrorCode.EXAM_ATTEMPT_NOT_ACTIVE, "请先开始作答，或当前试卷已交卷");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!running.getEndTime().isAfter(now)) {
            finalizeAttempt(running, true);
            throw new AttemptExpiredException("作答时间已结束，试卷已自动交卷");
        }
        if (submit.getAnswers() == null || submit.getAnswers().isEmpty()) {
            return Collections.emptyList();
        }
        List<ExamQuestion> questions = examQuestionMapper.selectList(
                new LambdaQueryWrapper<ExamQuestion>().eq(ExamQuestion::getPaperId, paperId));
        Map<Long, ExamQuestion> questionMap = questions.stream()
                .collect(Collectors.toMap(ExamQuestion::getId, Function.identity()));
        Set<Long> submittedQuestionIds = new HashSet<>();
        for (ExamAnswerSubmitDTO.AnswerItem item : submit.getAnswers()) {
            if (item == null || item.getQuestionId() == null || !submittedQuestionIds.add(item.getQuestionId())) {
                throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID,
                        "答案列表包含空题目或重复题目");
            }
            if (!questionMap.containsKey(item.getQuestionId())) {
                throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID,
                        "答案中包含不属于当前试卷的题目");
            }
        }

        Map<Long, ExamAnswer> answerMap = examAnswerMapper.selectList(
                        new LambdaQueryWrapper<ExamAnswer>().eq(ExamAnswer::getAttemptId, running.getId()))
                .stream().collect(Collectors.toMap(ExamAnswer::getQuestionId, Function.identity()));

        List<ExamPaperVO.ExamQuestionResultVO> results = new ArrayList<>();
        for (ExamAnswerSubmitDTO.AnswerItem item : submit.getAnswers()) {
            ExamQuestion question = questionMap.get(item.getQuestionId());
            boolean correct = ObjectiveJudge.isCorrect(question, item.getAnswer());
            int gained = correct ? (question.getScore() == null ? 0 : question.getScore()) : 0;

            ExamAnswer existing = answerMap.get(question.getId());
            if (existing == null) {
                ExamAnswer answer = new ExamAnswer();
                answer.setPaperId(paperId);
                answer.setAttemptId(running.getId());
                answer.setQuestionId(question.getId());
                answer.setUserId(userId);
                answer.setAnswer(item.getAnswer());
                answer.setIsCorrect(correct ? 1 : 0);
                answer.setScore(gained);
                answer.setCreateTime(now);
                answer.setUpdateTime(now);
                examAnswerMapper.insert(answer);
                answerMap.put(question.getId(), answer);
            } else {
                existing.setAnswer(item.getAnswer());
                existing.setIsCorrect(correct ? 1 : 0);
                existing.setScore(gained);
                existing.setUpdateTime(now);
                examAnswerMapper.updateById(existing);
            }

            ExamPaperVO.ExamQuestionResultVO result = new ExamPaperVO.ExamQuestionResultVO();
            result.setQuestionId(question.getId());
            result.setCorrect(correct);
            result.setScore(gained);
            result.setCorrectAnswer(question.getAnswer());
            result.setMyAnswer(item.getAnswer());
            result.setExplanation(question.getExplanation());
            results.add(result);
        }
        refreshAttemptObjectiveScore(running, answerMap.values());
        return results;
    }

    /** 开始作答：存在进行中会话直接返回（过期会话先自动交卷）。 */
    @Transactional(rollbackFor = Exception.class)
    public ExamAttemptVO startAttempt(Long paperId, Long userId) {
        ExamPaper paper = examPaperMapper.selectByIdForUpdate(paperId);
        if (paper == null) {
            throw new BusinessException(ProblemErrorCode.EXAM_PAPER_NOT_FOUND, "试卷不存在: " + paperId);
        }
        requirePublishedOrAdmin(paper);
        ExamAttempt running = findRunningAttempt(paperId, userId);
        if (running != null) {
            if (!running.getEndTime().isAfter(LocalDateTime.now())) {
                finalizeAttempt(running, true); // 过期会话先自动交卷
            } else {
                return toAttemptVO(running);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        int duration = paper.getDurationMinutes() == null ? 120 : paper.getDurationMinutes();
        ExamAttempt attempt = new ExamAttempt();
        attempt.setPaperId(paperId);
        attempt.setUserId(userId);
        attempt.setStatus(ExamAttempt.STATUS_RUNNING);
        attempt.setStartTime(now);
        attempt.setEndTime(now.plusMinutes(duration));
        attempt.setObjectiveScore(0);
        attempt.setCodingScore(0);
        attempt.setTotalScore(0);
        attempt.setCreateTime(now);
        attempt.setUpdateTime(now);
        examAttemptMapper.insert(attempt);
        log.info("【训练会话】开始作答 attemptId={}, paperId={}, userId={}, 时长={}分钟",
                attempt.getId(), paperId, userId, duration);
        return toAttemptVO(attempt);
    }

    /** 读取当前会话（无进行中会话返回最近一次已交卷记录）。 */
    @Transactional(rollbackFor = Exception.class)
    public ExamAttemptVO getAttempt(Long paperId, Long userId) {
        ExamPaper paper = requireExistingPaper(paperId);
        ExamAttempt running = findRunningAttempt(paperId, userId);
        ExamAttempt latest = running == null ? latestAttempt(paperId, userId) : running;
        requirePublishedOrAdminOrOwnAttempt(paper, latest);
        if (running != null) {
            if (!running.getEndTime().isAfter(LocalDateTime.now())) {
                return finalizeAttempt(running, true);
            }
            return toAttemptVO(running);
        }
        return toAttemptVO(latest);
    }

    /** 主动交卷（过期会话标记为超时交卷）。 */
    @Transactional(rollbackFor = Exception.class)
    public ExamAttemptVO finishAttempt(Long paperId, Long userId) {
        ExamPaper paper = requireExistingPaper(paperId);
        ExamAttempt running = findRunningAttempt(paperId, userId);
        ExamAttempt latest = running == null ? latestAttempt(paperId, userId) : running;
        requirePublishedOrAdminOrOwnAttempt(paper, latest);
        if (running == null) {
            return toAttemptVO(latest);
        }
        boolean expired = !running.getEndTime().isAfter(LocalDateTime.now());
        return finalizeAttempt(running, expired);
    }

    /** 定时任务：批量自动交卷所有已过期的进行中会话。 */
    @Transactional(rollbackFor = Exception.class)
    public int autoFinalizeExpired() {
        List<ExamAttempt> expired = examAttemptMapper.selectList(
                new LambdaQueryWrapper<ExamAttempt>()
                        .eq(ExamAttempt::getStatus, ExamAttempt.STATUS_RUNNING)
                        .le(ExamAttempt::getEndTime, LocalDateTime.now()));
        int count = 0;
        for (ExamAttempt candidate : expired) {
            ExamAttempt attempt = examAttemptMapper.selectByIdForUpdate(candidate.getId());
            if (attempt != null && ExamAttempt.STATUS_RUNNING.equals(attempt.getStatus())
                    && !attempt.getEndTime().isAfter(LocalDateTime.now())) {
                finalizeAttempt(attempt, true);
                count++;
            }
        }
        if (count > 0) {
            log.info("【训练会话】自动交卷 {} 个过期会话", count);
        }
        return count;
    }

    private ExamAttempt findRunningAttempt(Long paperId, Long userId) {
        return examAttemptMapper.selectRunningByPaperUserForUpdate(paperId, userId);
    }

    private ExamPaper requireExistingPaper(Long paperId) {
        ExamPaper paper = examPaperMapper.selectById(paperId);
        if (paper == null) {
            throw new BusinessException(ProblemErrorCode.EXAM_PAPER_NOT_FOUND, "试卷不存在: " + paperId);
        }
        return paper;
    }

    private void requirePublishedOrAdmin(ExamPaper paper) {
        if (!Integer.valueOf(1).equals(paper.getIsPublished()) && !SecurityUtils.isAdmin()) {
            throw new ForbiddenException("试卷尚未发布");
        }
    }

    private void requirePublishedOrAdminOrOwnAttempt(ExamPaper paper, ExamAttempt attempt) {
        if (Integer.valueOf(1).equals(paper.getIsPublished()) || SecurityUtils.isAdmin()) {
            return;
        }
        if (attempt == null) {
            throw new ForbiddenException("试卷尚未发布");
        }
    }

    private ExamAttempt latestAttempt(Long paperId, Long userId) {
        return examAttemptMapper.selectOne(
                new LambdaQueryWrapper<ExamAttempt>()
                        .eq(ExamAttempt::getPaperId, paperId)
                        .eq(ExamAttempt::getUserId, userId)
                        .orderByDesc(ExamAttempt::getId)
                        .last("LIMIT 1"));
    }

    /** 交卷：客观题按最新作答判分；编程题将会话内最好判分折算到试卷设置的单题分值。 */
    private ExamAttemptVO finalizeAttempt(ExamAttempt attempt, boolean auto) {
        LocalDateTime now = LocalDateTime.now();
        int objectiveScore = examAnswerMapper.selectList(
                        new LambdaQueryWrapper<ExamAnswer>()
                                .eq(ExamAnswer::getPaperId, attempt.getPaperId())
                                .eq(ExamAnswer::getAttemptId, attempt.getId()))
                .stream().mapToInt(a -> a.getScore() == null ? 0 : a.getScore()).sum();

        int codingScore = 0;
        List<ExamPaperProblem> problems = examPaperProblemMapper.selectList(
                new LambdaQueryWrapper<ExamPaperProblem>()
                        .eq(ExamPaperProblem::getPaperId, attempt.getPaperId()));
        if (!problems.isEmpty()) {
            List<Long> problemIds = problems.stream()
                    .map(ExamPaperProblem::getProblemId).toList();
            LocalDateTime scoreDeadline = now.isBefore(attempt.getEndTime()) ? now : attempt.getEndTime();
            Map<Long, Integer> bestScoreByProblem = submissionAttemptQuery.findBestScores(
                            attempt.getUserId(), problemIds,
                            attempt.getStartTime(), scoreDeadline)
                    .stream().collect(Collectors.toMap(
                            SubmissionAttemptQuery.SubmissionAttemptScore::problemId,
                            SubmissionAttemptQuery.SubmissionAttemptScore::bestScore,
                            Math::max));
            codingScore = problems.stream().mapToInt(problem -> {
                int paperPoints = problem.getScore() == null ? 0 : Math.max(0, problem.getScore());
                int rawPercent = bestScoreByProblem.getOrDefault(problem.getProblemId(), 0);
                int boundedPercent = Math.max(0, Math.min(100, rawPercent));
                return (int) Math.round((double) paperPoints * boundedPercent / 100.0);
            }).sum();
        }

        attempt.setStatus(auto ? ExamAttempt.STATUS_TIMEOUT : ExamAttempt.STATUS_SUBMITTED);
        attempt.setSubmitTime(now);
        attempt.setObjectiveScore(objectiveScore);
        attempt.setCodingScore(codingScore);
        attempt.setTotalScore(objectiveScore + codingScore);
        attempt.setUpdateTime(now);
        examAttemptMapper.updateById(attempt);
        log.info("【训练会话】交卷 attemptId={}, auto={}, 客观={}, 编程={}, 总分={}",
                attempt.getId(), auto, objectiveScore, codingScore, objectiveScore + codingScore);
        return toAttemptVO(attempt);
    }

    /** 作答提交后刷新进行中会话的客观题得分 */
    private void refreshAttemptObjectiveScore(ExamAttempt running, java.util.Collection<ExamAnswer> answers) {
        int objectiveScore = answers.stream().mapToInt(a -> a.getScore() == null ? 0 : a.getScore()).sum();
        running.setObjectiveScore(objectiveScore);
        running.setUpdateTime(LocalDateTime.now());
        examAttemptMapper.updateById(running);
    }

    private ExamAttemptVO toAttemptVO(ExamAttempt attempt) {
        if (attempt == null) {
            return null;
        }
        ExamAttemptVO vo = new ExamAttemptVO();
        vo.setId(attempt.getId());
        vo.setPaperId(attempt.getPaperId());
        vo.setStatus(attempt.getStatus());
        vo.setStartTime(attempt.getStartTime());
        vo.setEndTime(attempt.getEndTime());
        vo.setSubmitTime(attempt.getSubmitTime());
        vo.setObjectiveScore(attempt.getObjectiveScore());
        vo.setCodingScore(attempt.getCodingScore());
        vo.setTotalScore(attempt.getTotalScore());
        vo.setRemainingSeconds(ExamAttempt.STATUS_RUNNING.equals(attempt.getStatus())
                ? Math.max(0, java.time.Duration.between(LocalDateTime.now(), attempt.getEndTime()).getSeconds())
                : 0L);
        List<ExamAnswer> answers = examAnswerMapper.selectList(
                new LambdaQueryWrapper<ExamAnswer>().eq(ExamAnswer::getAttemptId, attempt.getId()));
        if (answers != null && !answers.isEmpty()) {
            List<ExamQuestion> questionRows = examQuestionMapper.selectBatchIds(
                    answers.stream().map(ExamAnswer::getQuestionId).toList());
            Map<Long, ExamQuestion> questionsById = (questionRows == null ? List.<ExamQuestion>of() : questionRows).stream()
                    .collect(Collectors.toMap(ExamQuestion::getId, Function.identity()));
            for (ExamAnswer answer : answers) {
                ExamPaperVO.ExamAttemptAnswerVO answerVO = new ExamPaperVO.ExamAttemptAnswerVO();
                answerVO.setQuestionId(answer.getQuestionId());
                answerVO.setAnswer(answer.getAnswer());
                vo.getAnswers().add(answerVO);

                ExamQuestion question = questionsById.get(answer.getQuestionId());
                if (question != null) {
                    ExamPaperVO.ExamQuestionResultVO resultVO = new ExamPaperVO.ExamQuestionResultVO();
                    resultVO.setQuestionId(question.getId());
                    resultVO.setCorrect(Integer.valueOf(1).equals(answer.getIsCorrect()));
                    resultVO.setScore(answer.getScore() == null ? 0 : answer.getScore());
                    resultVO.setCorrectAnswer(question.getAnswer());
                    resultVO.setMyAnswer(answer.getAnswer());
                    resultVO.setExplanation(question.getExplanation());
                    vo.getResults().add(resultVO);
                }
            }
        }
        return vo;
    }
}
