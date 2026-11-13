package com.musealgo.oj.problem.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musealgo.oj.api.submission.SubmissionAttemptQuery;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.common.exception.CommonErrorCode;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.problem.entity.ExamAnswer;
import com.musealgo.oj.problem.entity.ExamAttempt;
import com.musealgo.oj.problem.entity.ExamPaper;
import com.musealgo.oj.problem.entity.ExamPaperProblem;
import com.musealgo.oj.problem.entity.ExamQuestion;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.ProblemSample;
import com.musealgo.oj.problem.entity.vo.ExamPaperAnswerProblemVO;
import com.musealgo.oj.problem.entity.vo.ExamPaperAnswerVO;
import com.musealgo.oj.problem.error.ProblemErrorCode;
import com.musealgo.oj.problem.mapper.ExamAnswerMapper;
import com.musealgo.oj.problem.mapper.ExamAttemptMapper;
import com.musealgo.oj.problem.mapper.ExamPaperMapper;
import com.musealgo.oj.problem.mapper.ExamPaperProblemMapper;
import com.musealgo.oj.problem.mapper.ExamQuestionMapper;
import com.musealgo.oj.problem.mapper.ProblemMapper;
import com.musealgo.oj.problem.mapper.ProblemSampleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 成套试卷整卷答题页的读模型。
 *
 * <p>所有字段都按「作答期可见」裁剪：不含客观题答案/解析、不含题目难度/标签/通过率/提示、
 * 不含隐藏测试点。会话归属、状态和时间窗在这里统一校验，前端只负责展示。</p>
 */
@Service
@RequiredArgsConstructor
public class ExamPaperAnswerApplicationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ExamPaperMapper examPaperMapper;
    private final ExamPaperProblemMapper examPaperProblemMapper;
    private final ExamQuestionMapper examQuestionMapper;
    private final ExamAnswerMapper examAnswerMapper;
    private final ExamAttemptMapper examAttemptMapper;
    private final ProblemMapper problemMapper;
    private final ProblemSampleMapper problemSampleMapper;
    private final SubmissionAttemptQuery submissionAttemptQuery;

    public ExamPaperAnswerVO paper(Long paperId, Long attemptId) {
        Long userId = requireUserId();
        AttemptContext context = requireAttempt(paperId, attemptId, userId);
        ExamAttempt attempt = context.attempt();
        boolean canSubmit = context.canSubmit();

        ExamPaperAnswerVO vo = new ExamPaperAnswerVO();
        vo.setPaperId(paperId);
        vo.setAttemptId(attemptId);
        vo.setTitle(context.paper().getTitle());
        vo.setDescription(context.paper().getDescription());
        vo.setStatus(context.effectiveStatus());
        vo.setServerTime(LocalDateTime.now());
        vo.setDeadline(attempt.getEndTime());
        vo.setRemainingSeconds(remainingSeconds(attempt, context.effectiveStatus()));
        vo.setObjectiveScore(attempt.getObjectiveScore());
        vo.setCodingScore(attempt.getCodingScore());
        vo.setTotalScore(attempt.getTotalScore());
        vo.setCanSubmit(canSubmit);
        vo.setCanSelfTest(canSubmit);
        vo.setObjectiveQuestions(buildObjectiveQuestions(paperId, attemptId));
        vo.setCodingProblems(buildCodingProblems(attempt));
        vo.setGradingPending(canSubmit && hasPendingCodingSubmission(attempt));
        return vo;
    }

    public ExamPaperAnswerProblemVO problem(Long paperId, Long attemptId, Long problemId) {
        Long userId = requireUserId();
        AttemptContext context = requireAttempt(paperId, attemptId, userId);
        ExamPaperProblem relation = examPaperProblemMapper.selectOne(
                new LambdaQueryWrapper<ExamPaperProblem>()
                        .eq(ExamPaperProblem::getPaperId, paperId)
                        .eq(ExamPaperProblem::getProblemId, problemId)
                        .last("LIMIT 1"));
        if (relation == null) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "题目不属于当前试卷");
        }
        Problem problem = problemMapper.selectById(problemId);
        if (problem == null) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_NOT_FOUND, "题目不存在");
        }

        ExamPaperAnswerProblemVO vo = new ExamPaperAnswerProblemVO();
        vo.setProblemId(problemId);
        vo.setTitle(problem.getTitle());
        vo.setContent(problem.getContent());
        vo.setInputDesc(problem.getInputDesc());
        vo.setOutputDesc(problem.getOutputDesc());
        vo.setSamples(problemSampleMapper.selectList(
                new LambdaQueryWrapper<ProblemSample>()
                        .eq(ProblemSample::getProblemId, problemId)
                        .orderByAsc(ProblemSample::getSampleOrder)));
        vo.setTimeLimit(problem.getTimeLimit());
        vo.setMemoryLimit(problem.getMemoryLimit());
        vo.setScore(relation.getScore());
        vo.setCanSubmit(context.canSubmit());

        SubmissionAttemptQuery.SubmissionAttemptSummary summary = summaryFor(attemptId, problemId);
        vo.setMyBestScore(summary == null ? 0 : summary.bestScore());
        vo.setMyStatus(statusOf(vo.getMyBestScore()));
        return vo;
    }

    private List<ExamPaperAnswerVO.ObjectiveQuestionVO> buildObjectiveQuestions(Long paperId, Long attemptId) {
        List<ExamQuestion> questions = examQuestionMapper.selectList(
                new LambdaQueryWrapper<ExamQuestion>()
                        .eq(ExamQuestion::getPaperId, paperId)
                        .orderByAsc(ExamQuestion::getQuestionOrder));
        if (questions.isEmpty()) {
            return List.of();
        }
        Map<Long, ExamAnswer> answers = examAnswerMapper.selectList(
                        new LambdaQueryWrapper<ExamAnswer>().eq(ExamAnswer::getAttemptId, attemptId))
                .stream().collect(Collectors.toMap(ExamAnswer::getQuestionId, Function.identity(), (left, right) -> right));

        List<ExamPaperAnswerVO.ObjectiveQuestionVO> result = new ArrayList<>();
        for (ExamQuestion question : questions) {
            ExamPaperAnswerVO.ObjectiveQuestionVO item = new ExamPaperAnswerVO.ObjectiveQuestionVO();
            item.setId(question.getId());
            item.setQuestionType(question.getQuestionType());
            item.setStatement(question.getStatement());
            item.setOptions(parseOptions(question.getOptions()));
            item.setScore(question.getScore());
            item.setQuestionOrder(question.getQuestionOrder());
            ExamAnswer answer = answers.get(question.getId());
            if (answer != null) {
                item.setMyAnswer(answer.getAnswer());
                item.setCorrect(Integer.valueOf(1).equals(answer.getIsCorrect()));
                item.setGainedScore(answer.getScore() == null ? 0 : answer.getScore());
            }
            result.add(item);
        }
        return result;
    }

    private List<ExamPaperAnswerVO.CodingProblemVO> buildCodingProblems(ExamAttempt attempt) {
        List<ExamPaperProblem> relations = examPaperProblemMapper.selectList(
                new LambdaQueryWrapper<ExamPaperProblem>()
                        .eq(ExamPaperProblem::getPaperId, attempt.getPaperId())
                        .orderByAsc(ExamPaperProblem::getProblemOrder));
        if (relations.isEmpty()) {
            return List.of();
        }
        List<Long> problemIds = relations.stream().map(ExamPaperProblem::getProblemId).toList();
        Map<Long, Problem> problems = problemMapper.selectBatchIds(problemIds).stream()
                .collect(Collectors.toMap(Problem::getId, Function.identity()));
        Map<Long, SubmissionAttemptQuery.SubmissionAttemptSummary> summaries = summaries(attempt, problemIds);

        List<ExamPaperAnswerVO.CodingProblemVO> result = new ArrayList<>();
        for (ExamPaperProblem relation : relations) {
            ExamPaperAnswerVO.CodingProblemVO item = new ExamPaperAnswerVO.CodingProblemVO();
            item.setProblemId(relation.getProblemId());
            item.setProblemOrder(relation.getProblemOrder());
            Problem problem = problems.get(relation.getProblemId());
            item.setTitle(problem == null ? "题目 #" + relation.getProblemId() : problem.getTitle());
            item.setScore(relation.getScore());
            SubmissionAttemptQuery.SubmissionAttemptSummary summary = summaries.get(relation.getProblemId());
            int bestScore = summary == null ? 0 : summary.bestScore();
            item.setMyBestScore(bestScore);
            item.setAttemptCount(summary == null ? 0 : summary.attemptCount());
            item.setPending(summary != null && summary.pendingCount() > 0);
            item.setMyStatus(statusOf(bestScore));
            result.add(item);
        }
        return result;
    }

    private Map<Long, SubmissionAttemptQuery.SubmissionAttemptSummary> summaries(
            ExamAttempt attempt, List<Long> problemIds) {
        if (ExamAttempt.SCORING_BOUND.equals(attempt.getScoringVersion())) {
            return submissionAttemptQuery.summarizeByAttempt(attempt.getId(), problemIds).stream()
                    .collect(Collectors.toMap(
                            SubmissionAttemptQuery.SubmissionAttemptSummary::problemId,
                            Function.identity(), (left, right) -> right, LinkedHashMap::new));
        }
        // 历史会话没有绑定提交，退回时间窗最佳分；只用于兼容，不影响新会话。
        LocalDateTime deadline = attempt.getEndTime() == null ? LocalDateTime.now() : attempt.getEndTime();
        return submissionAttemptQuery.findBestScores(
                        attempt.getUserId(), problemIds, attempt.getStartTime(), deadline).stream()
                .collect(Collectors.toMap(
                        SubmissionAttemptQuery.SubmissionAttemptScore::problemId,
                        score -> new SubmissionAttemptQuery.SubmissionAttemptSummary(
                                score.problemId(), score.bestScore(), 0, 0),
                        (left, right) -> right, LinkedHashMap::new));
    }

    private SubmissionAttemptQuery.SubmissionAttemptSummary summaryFor(Long attemptId, Long problemId) {
        ExamAttempt attempt = examAttemptMapper.selectById(attemptId);
        if (attempt == null) {
            return null;
        }
        return summaries(attempt, List.of(problemId)).get(problemId);
    }

    private boolean hasPendingCodingSubmission(ExamAttempt attempt) {
        List<Long> problemIds = examPaperProblemMapper.selectList(
                        new LambdaQueryWrapper<ExamPaperProblem>()
                                .eq(ExamPaperProblem::getPaperId, attempt.getPaperId()))
                .stream().map(ExamPaperProblem::getProblemId).distinct().toList();
        if (problemIds.isEmpty()) {
            return false;
        }
        if (ExamAttempt.SCORING_BOUND.equals(attempt.getScoringVersion())) {
            return submissionAttemptQuery.countPendingByAttempt(attempt.getId(), problemIds) > 0;
        }
        LocalDateTime deadline = attempt.getEndTime() == null ? LocalDateTime.now() : attempt.getEndTime();
        return submissionAttemptQuery.countPending(
                attempt.getUserId(), problemIds, attempt.getStartTime(), deadline) > 0;
    }

    private AttemptContext requireAttempt(Long paperId, Long attemptId, Long userId) {
        ExamAttempt attempt = examAttemptMapper.selectById(attemptId);
        if (attempt == null || !paperId.equals(attempt.getPaperId())) {
            throw new BusinessException(ProblemErrorCode.EXAM_ATTEMPT_NOT_ACTIVE, "作答会话不存在");
        }
        if (!userId.equals(attempt.getUserId())) {
            throw new ForbiddenException("无权访问该作答会话");
        }
        ExamPaper paper = examPaperMapper.selectById(paperId);
        if (paper == null) {
            throw new BusinessException(ProblemErrorCode.EXAM_PAPER_NOT_FOUND, "试卷不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean running = ExamAttempt.STATUS_RUNNING.equals(attempt.getStatus());
        boolean expired = running && (attempt.getEndTime() == null || !attempt.getEndTime().isAfter(now));
        String effectiveStatus = expired ? ExamAttempt.STATUS_TIMEOUT : attempt.getStatus();
        boolean canSubmit = running && !expired;
        return new AttemptContext(attempt, paper, effectiveStatus, canSubmit);
    }

    private static long remainingSeconds(ExamAttempt attempt, String effectiveStatus) {
        if (!ExamAttempt.STATUS_RUNNING.equals(effectiveStatus) || attempt.getEndTime() == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(LocalDateTime.now(), attempt.getEndTime()).getSeconds());
    }

    private static String statusOf(int bestScore) {
        if (bestScore >= 100) return "SOLVED";
        if (bestScore > 0) return "ATTEMPTED";
        return "UNTOUCHED";
    }

    private static Long requireUserId() {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            throw new BusinessException(CommonErrorCode.AUTH_REQUIRED, "请先登录");
        }
        return userId;
    }

    private static List<Map<String, String>> parseOptions(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<Map<String, String>>>() {
            });
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private record AttemptContext(
            ExamAttempt attempt, ExamPaper paper, String effectiveStatus, boolean canSubmit) {
    }
}
