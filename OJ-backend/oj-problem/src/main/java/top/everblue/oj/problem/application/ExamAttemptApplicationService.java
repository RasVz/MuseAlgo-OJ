package top.everblue.oj.problem.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.api.submission.SubmissionAttemptQuery;
import top.everblue.oj.problem.domain.ObjectiveJudge;
import top.everblue.oj.problem.entity.ExamAnswer;
import top.everblue.oj.problem.entity.ExamAttempt;
import top.everblue.oj.problem.entity.ExamPaper;
import top.everblue.oj.problem.entity.ExamPaperProblem;
import top.everblue.oj.problem.entity.ExamQuestion;
import top.everblue.oj.problem.entity.dto.ExamAnswerSubmitDTO;
import top.everblue.oj.problem.entity.vo.ExamAttemptVO;
import top.everblue.oj.problem.entity.vo.ExamPaperVO;
import top.everblue.oj.problem.mapper.ExamAnswerMapper;
import top.everblue.oj.problem.mapper.ExamAttemptMapper;
import top.everblue.oj.problem.mapper.ExamPaperMapper;
import top.everblue.oj.problem.mapper.ExamPaperProblemMapper;
import top.everblue.oj.problem.mapper.ExamQuestionMapper;
import top.everblue.oj.problem.service.impl.AttemptExpiredException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 训练试卷作答会话域：开始作答 / 提交客观题 / 交卷（主动与超时自动）/ 过期批量收卷。
 * <p>
 * 从 {@link top.everblue.oj.problem.service.impl.ExamPaperServiceImpl} 拆出
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
        if (submit.getAnswers() == null || submit.getAnswers().isEmpty()) {
            return Collections.emptyList();
        }
        ExamAttempt running = findRunningAttempt(paperId, userId);
        if (running == null) {
            throw new IllegalStateException("请先开始作答，或当前试卷已交卷");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!running.getEndTime().isAfter(now)) {
            finalizeAttempt(running, true);
            throw new AttemptExpiredException("作答时间已结束，试卷已自动交卷");
        }
        List<ExamQuestion> questions = examQuestionMapper.selectList(
                new LambdaQueryWrapper<ExamQuestion>().eq(ExamQuestion::getPaperId, paperId));
        Map<Long, ExamQuestion> questionMap = questions.stream()
                .collect(Collectors.toMap(ExamQuestion::getId, Function.identity()));

        List<ExamPaperVO.ExamQuestionResultVO> results = new ArrayList<>();
        for (ExamAnswerSubmitDTO.AnswerItem item : submit.getAnswers()) {
            ExamQuestion question = questionMap.get(item.getQuestionId());
            if (question == null) {
                continue;
            }
            boolean correct = ObjectiveJudge.isCorrect(question, item.getAnswer());
            int gained = correct ? (question.getScore() == null ? 0 : question.getScore()) : 0;

            ExamAnswer existing = examAnswerMapper.selectOne(
                    new LambdaQueryWrapper<ExamAnswer>()
                            .eq(ExamAnswer::getAttemptId, running.getId())
                            .eq(ExamAnswer::getQuestionId, question.getId())
                            .last("LIMIT 1"));
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
        refreshAttemptObjectiveScore(running);
        return results;
    }

    /** 开始作答：存在进行中会话直接返回（过期会话先自动交卷）。 */
    @Transactional(rollbackFor = Exception.class)
    public ExamAttemptVO startAttempt(Long paperId, Long userId) {
        ExamPaper paper = examPaperMapper.selectByIdForUpdate(paperId);
        if (paper == null) {
            throw new IllegalArgumentException("试卷不存在: " + paperId);
        }
        ExamAttempt running = findRunningAttempt(paperId, userId);
        if (running != null) {
            if (running.getEndTime().isBefore(LocalDateTime.now())) {
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
        ExamAttempt running = findRunningAttempt(paperId, userId);
        if (running != null) {
            if (running.getEndTime().isBefore(LocalDateTime.now())) {
                return finalizeAttempt(running, true);
            }
            return toAttemptVO(running);
        }
        return toAttemptVO(latestAttempt(paperId, userId));
    }

    /** 主动交卷（过期会话标记为超时交卷）。 */
    @Transactional(rollbackFor = Exception.class)
    public ExamAttemptVO finishAttempt(Long paperId, Long userId) {
        ExamAttempt running = findRunningAttempt(paperId, userId);
        if (running == null) {
            return toAttemptVO(latestAttempt(paperId, userId));
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
                        .lt(ExamAttempt::getEndTime, LocalDateTime.now()));
        int count = 0;
        for (ExamAttempt attempt : expired) {
            finalizeAttempt(attempt, true);
            count++;
        }
        if (count > 0) {
            log.info("【训练会话】自动交卷 {} 个过期会话", count);
        }
        return count;
    }

    private ExamAttempt findRunningAttempt(Long paperId, Long userId) {
        return examAttemptMapper.selectOne(
                new LambdaQueryWrapper<ExamAttempt>()
                        .eq(ExamAttempt::getPaperId, paperId)
                        .eq(ExamAttempt::getUserId, userId)
                        .eq(ExamAttempt::getStatus, ExamAttempt.STATUS_RUNNING)
                        .last("LIMIT 1"));
    }

    private ExamAttempt latestAttempt(Long paperId, Long userId) {
        return examAttemptMapper.selectOne(
                new LambdaQueryWrapper<ExamAttempt>()
                        .eq(ExamAttempt::getPaperId, paperId)
                        .eq(ExamAttempt::getUserId, userId)
                        .orderByDesc(ExamAttempt::getId)
                        .last("LIMIT 1"));
    }

    /** 交卷：客观题 = 最新作答判分之和；编程题 = 会话时间窗内每题最好成绩之和 */
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
            codingScore = submissionAttemptQuery.findBestScores(
                            attempt.getUserId(), problemIds,
                            attempt.getStartTime(), attempt.getEndTime())
                    .stream().mapToInt(SubmissionAttemptQuery.SubmissionAttemptScore::bestScore).sum();
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
    private void refreshAttemptObjectiveScore(ExamAttempt running) {
        int objectiveScore = examAnswerMapper.selectList(
                        new LambdaQueryWrapper<ExamAnswer>()
                                .eq(ExamAnswer::getAttemptId, running.getId()))
                .stream().mapToInt(a -> a.getScore() == null ? 0 : a.getScore()).sum();
        running.setObjectiveScore(objectiveScore);
        running.setUpdateTime(LocalDateTime.now());
        examAttemptMapper.updateById(running);
    }

    private static ExamAttemptVO toAttemptVO(ExamAttempt attempt) {
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
        return vo;
    }
}
