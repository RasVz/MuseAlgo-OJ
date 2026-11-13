package com.musealgo.oj.problem.service.impl;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.problem.error.ProblemErrorCode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.musealgo.oj.problem.application.ExamAttemptApplicationService;
import com.musealgo.oj.problem.application.ExamPaperStatsApplicationService;
import com.musealgo.oj.problem.domain.ObjectiveJudge;
import com.musealgo.oj.problem.entity.ExamAttempt;
import com.musealgo.oj.problem.entity.ExamAnswer;
import com.musealgo.oj.problem.entity.ExamPaper;
import com.musealgo.oj.problem.entity.ExamPaperProblem;
import com.musealgo.oj.problem.entity.ExamQuestion;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.dto.ExamAnswerSubmitDTO;
import com.musealgo.oj.problem.entity.dto.ExamPaperDTO;
import com.musealgo.oj.problem.entity.vo.ExamAttemptVO;
import com.musealgo.oj.problem.entity.vo.ExamPaperVO;
import com.musealgo.oj.problem.entity.vo.ExamPaperStatsVO;
import com.musealgo.oj.problem.mapper.ExamAttemptMapper;
import com.musealgo.oj.problem.mapper.ExamAnswerMapper;
import com.musealgo.oj.problem.mapper.ExamPaperMapper;
import com.musealgo.oj.problem.mapper.ExamPaperProblemMapper;
import com.musealgo.oj.problem.mapper.ExamQuestionMapper;
import com.musealgo.oj.problem.mapper.ProblemMapper;
import com.musealgo.oj.problem.service.ExamPaperService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 训练试卷服务（含客观题提交即时判分）。
 * <p>
 * 2026-08-15 大服务类拆分：试卷管理域保留在本类（727 → ~340 行）；
 * 作答会话域 → {@link ExamAttemptApplicationService}，统计域 → {@link ExamPaperStatsApplicationService}。
 * 接口方法签名不变，事务注解随实现迁移至新类。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamPaperServiceImpl implements ExamPaperService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ExamPaperMapper examPaperMapper;
    private final ExamPaperProblemMapper examPaperProblemMapper;
    private final ExamQuestionMapper examQuestionMapper;
    private final ExamAnswerMapper examAnswerMapper;
    private final ExamAttemptMapper examAttemptMapper;
    private final ProblemMapper problemMapper;
    private final ExamAttemptApplicationService attemptApplicationService;
    private final ExamPaperStatsApplicationService statsApplicationService;

    @Override
    public Page<ExamPaper> listPapers(Integer page, Integer size, String category,
                                      String keyword, Boolean publishedOnly) {
        LambdaQueryWrapper<ExamPaper> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.hasText(category), ExamPaper::getCategory,
                        StringUtils.hasText(category) ? category.trim() : category)
                .eq(Boolean.TRUE.equals(publishedOnly), ExamPaper::getIsPublished, 1)
                .like(StringUtils.hasText(keyword), ExamPaper::getTitle,
                        StringUtils.hasText(keyword) ? keyword.trim() : keyword)
                .orderByDesc(ExamPaper::getUpdateTime).orderByDesc(ExamPaper::getId);
        Page<ExamPaper> result = examPaperMapper.selectPage(
                new Page<>(page == null ? 1 : page, size == null ? 10 : size), wrapper);
        if (!result.getRecords().isEmpty()) {
            List<Long> paperIds = result.getRecords().stream().map(ExamPaper::getId).toList();
            Map<Long, Long> codingCounts = examPaperProblemMapper.selectList(
                            new LambdaQueryWrapper<ExamPaperProblem>().select(ExamPaperProblem::getPaperId)
                                    .in(ExamPaperProblem::getPaperId, paperIds))
                    .stream().collect(Collectors.groupingBy(ExamPaperProblem::getPaperId, Collectors.counting()));
            Map<Long, Long> objectiveCounts = examQuestionMapper.selectList(
                            new LambdaQueryWrapper<ExamQuestion>().select(ExamQuestion::getPaperId)
                                    .in(ExamQuestion::getPaperId, paperIds))
                    .stream().collect(Collectors.groupingBy(ExamQuestion::getPaperId, Collectors.counting()));
            result.getRecords().forEach(paper -> paper.setProblemCount(Math.toIntExact(
                    codingCounts.getOrDefault(paper.getId(), 0L) + objectiveCounts.getOrDefault(paper.getId(), 0L))));
        }
        return result;
    }

    @Override
    public ExamPaperVO detail(Long id, boolean withAnswers) {
        ExamPaper paper = examPaperMapper.selectById(id);
        if (paper == null) {
            return null;
        }
        ExamPaperVO vo = new ExamPaperVO();
        vo.setId(paper.getId());
        vo.setTitle(paper.getTitle());
        vo.setDescription(paper.getDescription());
        vo.setCategory(paper.getCategory());
        vo.setLevel(paper.getLevel());
        vo.setDurationMinutes(paper.getDurationMinutes());
        vo.setTotalScore(paper.getTotalScore());
        vo.setProblemCount(paper.getProblemCount());
        vo.setIsPublished(paper.getIsPublished());
        vo.setHasAttempts(examAttemptMapper.selectCount(
                new LambdaQueryWrapper<ExamAttempt>().eq(ExamAttempt::getPaperId, id)) > 0);
        vo.setCreateTime(paper.getCreateTime());
        vo.setUpdateTime(paper.getUpdateTime());

        vo.setProblems(buildProblems(id));
        vo.setQuestions(buildQuestions(id, withAnswers));
        vo.setProblemCount(vo.getProblems().size() + vo.getQuestions().size());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long savePaper(ExamPaperDTO dto, Long createBy) {
        ExamPaper paper = dto.getId() == null ? new ExamPaper() : examPaperMapper.selectByIdForUpdate(dto.getId());
        if (paper == null) {
            throw new BusinessException(ProblemErrorCode.EXAM_PAPER_NOT_FOUND, "试卷不存在: " + dto.getId());
        }
        boolean hasAttempts = paper.getId() != null && examAttemptMapper.selectCount(
                new LambdaQueryWrapper<ExamAttempt>().eq(ExamAttempt::getPaperId, paper.getId())) > 0;
        if (hasAttempts) {
            if (hasStructuralChanges(dto, paper)) {
                throw new BusinessException(ProblemErrorCode.EXAM_PAPER_HAS_ATTEMPTS,
                        "试卷已有作答记录，题目、分值、时长和满分不可更改；请复制新卷后修改");
            }
        } else {
            validatePaperStructure(dto);
        }
        paper.setTitle(dto.getTitle());
        paper.setDescription(dto.getDescription());
        paper.setCategory(dto.getCategory());
        paper.setLevel(dto.getLevel());
        if (!hasAttempts) {
            paper.setDurationMinutes(dto.getDurationMinutes());
            paper.setTotalScore(dto.getTotalScore());
        }
        paper.setIsPublished(dto.getIsPublished() == null ? 0 : dto.getIsPublished());
        paper.setProblemCount((dto.getProblems() == null ? 0 : dto.getProblems().size())
                + (dto.getQuestions() == null ? 0 : dto.getQuestions().size()));
        LocalDateTime now = LocalDateTime.now();
        if (paper.getId() == null) {
            paper.setCreateBy(createBy);
            paper.setCreateTime(now);
            paper.setUpdateTime(now);
            examPaperMapper.insert(paper);
        } else {
            paper.setUpdateTime(now);
            examPaperMapper.updateById(paper);
            if (!hasAttempts) {
                examPaperProblemMapper.delete(
                        new LambdaQueryWrapper<ExamPaperProblem>().eq(ExamPaperProblem::getPaperId, paper.getId()));
            }
        }

        // 有作答记录的试卷保留其题目快照，避免改变历史分数含义。
        if (!hasAttempts && dto.getProblems() != null) {
            int order = 1;
            for (ExamPaperDTO.ExamPaperProblemItem item : dto.getProblems()) {
                if (item.getProblemId() == null) {
                    continue;
                }
                ExamPaperProblem relation = new ExamPaperProblem();
                relation.setPaperId(paper.getId());
                relation.setProblemId(item.getProblemId());
                relation.setProblemOrder(order++);
                relation.setScore(item.getScore() == null ? 0 : item.getScore());
                relation.setCreateTime(now);
                examPaperProblemMapper.insert(relation);
            }
        }

        if (!hasAttempts) {
            examQuestionMapper.delete(
                    new LambdaQueryWrapper<ExamQuestion>().eq(ExamQuestion::getPaperId, paper.getId()));
            examAnswerMapper.delete(
                    new LambdaQueryWrapper<ExamAnswer>().eq(ExamAnswer::getPaperId, paper.getId()));
            examAttemptMapper.delete(
                    new LambdaQueryWrapper<ExamAttempt>().eq(ExamAttempt::getPaperId, paper.getId()));
        }
        if (!hasAttempts && dto.getQuestions() != null) {
            int qOrder = 1;
            for (ExamPaperDTO.ExamQuestionItem item : dto.getQuestions()) {
                String normalizedAnswer = normalizeAndValidateQuestion(item, qOrder);
                ExamQuestion question = new ExamQuestion();
                question.setPaperId(paper.getId());
                question.setQuestionType(item.getQuestionType().trim().toUpperCase());
                question.setStatement(item.getStatement());
                question.setOptions(serializeOptions(item.getOptions()));
                question.setAnswer(normalizedAnswer);
                question.setExplanation(item.getExplanation());
                question.setScore(item.getScore() == null ? 0 : item.getScore());
                question.setQuestionOrder(qOrder++);
                question.setCreateTime(now);
                examQuestionMapper.insert(question);
            }
        }

        log.info("【训练试卷】保存 paperId={}, problems={}, questions={}", paper.getId(),
                dto.getProblems() == null ? 0 : dto.getProblems().size(),
                dto.getQuestions() == null ? 0 : dto.getQuestions().size());
        return paper.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePaper(Long id) {
        ExamPaper paper = examPaperMapper.selectByIdForUpdate(id);
        if (paper == null) {
            throw new BusinessException(ProblemErrorCode.EXAM_PAPER_NOT_FOUND, "试卷不存在: " + id);
        }
        if (examAttemptMapper.selectCount(
                new LambdaQueryWrapper<ExamAttempt>().eq(ExamAttempt::getPaperId, id)) > 0) {
            throw new BusinessException(ProblemErrorCode.EXAM_PAPER_HAS_ATTEMPTS,
                    "试卷已有作答记录，请取消发布以保留成绩");
        }
        ExamPaper deleted = new ExamPaper();
        deleted.setId(id);
        deleted.setUpdateTime(LocalDateTime.now());
        examPaperMapper.deleteById(deleted); // 逻辑删除同时保留非空更新时间
        examPaperProblemMapper.delete(
                new LambdaQueryWrapper<ExamPaperProblem>().eq(ExamPaperProblem::getPaperId, id));
        examQuestionMapper.delete(
                new LambdaQueryWrapper<ExamQuestion>().eq(ExamQuestion::getPaperId, id));
        examAnswerMapper.delete(
                new LambdaQueryWrapper<ExamAnswer>().eq(ExamAnswer::getPaperId, id));
        examAttemptMapper.delete(
                new LambdaQueryWrapper<ExamAttempt>().eq(ExamAttempt::getPaperId, id));
    }

    // ======================== 作答会话域委托 ========================

    @Override
    public List<ExamPaperVO.ExamQuestionResultVO> submitAnswers(
            Long paperId, Long userId, ExamAnswerSubmitDTO submit) {
        return attemptApplicationService.submitAnswers(paperId, userId, submit);
    }

    @Override
    public ExamAttemptVO startAttempt(Long paperId, Long userId) {
        return attemptApplicationService.startAttempt(paperId, userId);
    }

    @Override
    public ExamAttemptVO getAttempt(Long paperId, Long userId) {
        return attemptApplicationService.getAttempt(paperId, userId);
    }

    @Override
    public ExamAttemptVO finishAttempt(Long paperId, Long userId) {
        return attemptApplicationService.finishAttempt(paperId, userId);
    }

    @Override
    public int autoFinalizeExpired() {
        return attemptApplicationService.autoFinalizeExpired();
    }

    // ======================== 试卷域 ========================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long copyPaper(Long paperId, Long createBy) {
        ExamPaper source = examPaperMapper.selectById(paperId);
        if (source == null) {
            throw new BusinessException(ProblemErrorCode.EXAM_PAPER_NOT_FOUND, "试卷不存在: " + paperId);
        }
        LocalDateTime now = LocalDateTime.now();
        ExamPaper copy = new ExamPaper();
        copy.setTitle(source.getTitle() + "（副本）");
        copy.setDescription(source.getDescription());
        copy.setCategory(source.getCategory());
        copy.setLevel(source.getLevel());
        copy.setDurationMinutes(source.getDurationMinutes());
        copy.setTotalScore(source.getTotalScore());
        copy.setProblemCount(source.getProblemCount());
        copy.setIsPublished(0);
        copy.setCreateBy(createBy);
        copy.setCreateTime(now);
        copy.setUpdateTime(now);
        examPaperMapper.insert(copy);

        for (ExamPaperProblem relation : examPaperProblemMapper.selectList(
                new LambdaQueryWrapper<ExamPaperProblem>().eq(ExamPaperProblem::getPaperId, paperId))) {
            ExamPaperProblem copied = new ExamPaperProblem();
            copied.setPaperId(copy.getId());
            copied.setProblemId(relation.getProblemId());
            copied.setProblemOrder(relation.getProblemOrder());
            copied.setScore(relation.getScore());
            copied.setCreateTime(now);
            examPaperProblemMapper.insert(copied);
        }
        for (ExamQuestion question : examQuestionMapper.selectList(
                new LambdaQueryWrapper<ExamQuestion>().eq(ExamQuestion::getPaperId, paperId))) {
            ExamQuestion copied = new ExamQuestion();
            copied.setPaperId(copy.getId());
            copied.setQuestionType(question.getQuestionType());
            copied.setStatement(question.getStatement());
            copied.setOptions(question.getOptions());
            copied.setAnswer(question.getAnswer());
            copied.setExplanation(question.getExplanation());
            copied.setScore(question.getScore());
            copied.setQuestionOrder(question.getQuestionOrder());
            copied.setCreateTime(now);
            examQuestionMapper.insert(copied);
        }
        log.info("【训练试卷】复制 paperId={} -> {}", paperId, copy.getId());
        return copy.getId();
    }

    // ======================== 统计域委托 ========================

    @Override
    public ExamPaperStatsVO paperStats(Long paperId) {
        return statsApplicationService.paperStats(paperId);
    }

    @Override
    public Page<ExamPaperVO.ExamAttemptRowVO> listAttempts(Long paperId, Integer page, Integer size) {
        return statsApplicationService.listAttempts(paperId, page, size);
    }

    @Override
    public String buildAttemptsCsv(Long paperId) {
        return statsApplicationService.buildAttemptsCsv(paperId);
    }

    // ---- 组装 ----

    private List<ExamPaperVO.ExamPaperProblemVO> buildProblems(Long paperId) {
        List<ExamPaperProblem> relations = examPaperProblemMapper.selectList(
                new LambdaQueryWrapper<ExamPaperProblem>()
                        .eq(ExamPaperProblem::getPaperId, paperId)
                        .orderByAsc(ExamPaperProblem::getProblemOrder));
        if (relations.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> problemIds = relations.stream()
                .map(ExamPaperProblem::getProblemId).collect(Collectors.toSet());
        Map<Long, Problem> problemMap = problemMapper.selectBatchIds(problemIds).stream()
                .collect(Collectors.toMap(Problem::getId, Function.identity()));

        List<ExamPaperVO.ExamPaperProblemVO> problems = new ArrayList<>();
        for (ExamPaperProblem relation : relations) {
            Problem problem = problemMap.get(relation.getProblemId());
            ExamPaperVO.ExamPaperProblemVO item = new ExamPaperVO.ExamPaperProblemVO();
            item.setProblemId(relation.getProblemId());
            item.setProblemOrder(relation.getProblemOrder());
            item.setScore(relation.getScore());
            item.setTitle(problem == null ? "题目 #" + relation.getProblemId() : problem.getTitle());
            item.setDifficulty(problem == null ? null : problem.getDifficulty());
            problems.add(item);
        }
        return problems;
    }

    private List<ExamPaperVO.ExamQuestionVO> buildQuestions(Long paperId, boolean withAnswers) {
        List<ExamQuestion> questions = examQuestionMapper.selectList(
                new LambdaQueryWrapper<ExamQuestion>()
                        .eq(ExamQuestion::getPaperId, paperId)
                        .orderByAsc(ExamQuestion::getQuestionOrder));
        List<ExamPaperVO.ExamQuestionVO> result = new ArrayList<>();
        for (ExamQuestion question : questions) {
            ExamPaperVO.ExamQuestionVO vo = new ExamPaperVO.ExamQuestionVO();
            vo.setId(question.getId());
            vo.setQuestionType(question.getQuestionType());
            vo.setStatement(question.getStatement());
            vo.setOptions(parseOptions(question.getOptions()));
            if (withAnswers) {
                vo.setAnswer(question.getAnswer());
                vo.setExplanation(question.getExplanation());
            }
            vo.setScore(question.getScore());
            vo.setQuestionOrder(question.getQuestionOrder());
            result.add(vo);
        }
        return result;
    }

    private static String serializeOptions(List<ExamPaperDTO.ExamOptionItem> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        try {
            List<ExamPaperDTO.ExamOptionItem> normalized = options.stream().map(option -> {
                ExamPaperDTO.ExamOptionItem copy = new ExamPaperDTO.ExamOptionItem();
                copy.setKey(option.getKey().trim().toUpperCase());
                copy.setText(option.getText());
                return copy;
            }).toList();
            return OBJECT_MAPPER.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "选项格式不合法");
        }
    }

    private static List<Map<String, String>> parseOptions(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<Map<String, String>>>() {
            });
        } catch (Exception e) {
            log.warn("客观题选项 JSON 解析失败: {}", json);
            return new ArrayList<>();
        }
    }

    private void validatePaperStructure(ExamPaperDTO dto) {
        Set<Long> problemIds = new HashSet<>();
        if (dto.getProblems() != null) {
            for (ExamPaperDTO.ExamPaperProblemItem item : dto.getProblems()) {
                if (item == null || item.getProblemId() == null || !problemIds.add(item.getProblemId())) {
                    throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID,
                            "编程题不能为空且不能重复");
                }
            }
            if (!problemIds.isEmpty() && problemMapper.selectBatchIds(problemIds).size() != problemIds.size()) {
                throw new BusinessException(ProblemErrorCode.PROBLEM_NOT_FOUND, "试卷包含不存在的编程题");
            }
        }
        if (dto.getQuestions() != null) {
            int order = 1;
            for (ExamPaperDTO.ExamQuestionItem item : dto.getQuestions()) {
                if (item == null) {
                    throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID,
                            "客观题不能为空");
                }
                normalizeAndValidateQuestion(item, order++);
            }
        }
    }

    private String normalizeAndValidateQuestion(ExamPaperDTO.ExamQuestionItem item, int order) {
        String type = item.getQuestionType() == null ? "" : item.getQuestionType().trim().toUpperCase();
        if (!Set.of(ExamQuestion.TYPE_SINGLE, ExamQuestion.TYPE_MULTIPLE, ExamQuestion.TYPE_JUDGE).contains(type)) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID,
                    "第 " + order + " 题的题型不支持");
        }
        String normalized = ObjectiveJudge.normalize(type, item.getAnswer());
        if (normalized == null) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID,
                    "第 " + order + " 题的标准答案不合法");
        }
        if (ExamQuestion.TYPE_JUDGE.equals(type)) {
            return normalized;
        }
        Set<String> optionKeys = new HashSet<>();
        if (item.getOptions() != null) {
            for (ExamPaperDTO.ExamOptionItem option : item.getOptions()) {
                String key = option == null || option.getKey() == null
                        ? "" : option.getKey().trim().toUpperCase();
                if (!key.matches("[A-Z]") || !optionKeys.add(key)) {
                    throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID,
                            "第 " + order + " 题的选项标识必须唯一且为单个英文字母");
                }
            }
        }
        if (optionKeys.isEmpty()) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID,
                    "第 " + order + " 题至少需要一个选项");
        }
        Set<String> answerKeys = normalized.chars()
                .mapToObj(c -> String.valueOf((char) c)).collect(Collectors.toSet());
        if (answerKeys.size() != normalized.length() || !optionKeys.containsAll(answerKeys)
                || (ExamQuestion.TYPE_SINGLE.equals(type) && normalized.length() != 1)) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID,
                    "第 " + order + " 题的标准答案必须由有效选项组成且不能重复");
        }
        return normalized;
    }

    private boolean hasStructuralChanges(ExamPaperDTO dto, ExamPaper paper) {
        if (!java.util.Objects.equals(dto.getDurationMinutes(), paper.getDurationMinutes())
                || !java.util.Objects.equals(dto.getTotalScore(), paper.getTotalScore())) {
            return true;
        }
        List<ExamPaperProblem> currentProblems = examPaperProblemMapper.selectList(
                new LambdaQueryWrapper<ExamPaperProblem>().eq(ExamPaperProblem::getPaperId, paper.getId())
                        .orderByAsc(ExamPaperProblem::getProblemOrder));
        List<ExamPaperDTO.ExamPaperProblemItem> requestedProblems = dto.getProblems() == null
                ? List.of() : dto.getProblems();
        if (currentProblems.size() != requestedProblems.size()) {
            return true;
        }
        for (int i = 0; i < currentProblems.size(); i++) {
            ExamPaperProblem current = currentProblems.get(i);
            ExamPaperDTO.ExamPaperProblemItem requested = requestedProblems.get(i);
            if (requested == null || !java.util.Objects.equals(current.getProblemId(), requested.getProblemId())
                    || !java.util.Objects.equals(current.getScore(), requested.getScore())) {
                return true;
            }
        }
        List<ExamQuestion> currentQuestions = examQuestionMapper.selectList(
                new LambdaQueryWrapper<ExamQuestion>().eq(ExamQuestion::getPaperId, paper.getId())
                        .orderByAsc(ExamQuestion::getQuestionOrder));
        List<ExamPaperDTO.ExamQuestionItem> requestedQuestions = dto.getQuestions() == null
                ? List.of() : dto.getQuestions();
        if (currentQuestions.size() != requestedQuestions.size()) {
            return true;
        }
        for (int i = 0; i < currentQuestions.size(); i++) {
            ExamQuestion current = currentQuestions.get(i);
            ExamPaperDTO.ExamQuestionItem requested = requestedQuestions.get(i);
            if (requested == null) {
                return true;
            }
            String requestedType = requested.getQuestionType() == null
                    ? "" : requested.getQuestionType().trim().toUpperCase();
            String requestedOptions = safelySerializeOptions(requested.getOptions());
            String requestedAnswer = ObjectiveJudge.normalize(requestedType, requested.getAnswer());
            boolean answerMatches = java.util.Objects.equals(current.getAnswer(), requestedAnswer)
                    || java.util.Objects.equals(current.getAnswer(), requested.getAnswer());
            if (!java.util.Objects.equals(current.getQuestionType(), requestedType)
                    || !java.util.Objects.equals(current.getStatement(), requested.getStatement())
                    || !java.util.Objects.equals(normalizeStoredOptions(current.getOptions()), requestedOptions)
                    || !answerMatches
                    || !java.util.Objects.equals(current.getExplanation(), requested.getExplanation())
                    || !java.util.Objects.equals(current.getScore(), requested.getScore())) {
                return true;
            }
        }
        return false;
    }

    private static String safelySerializeOptions(List<ExamPaperDTO.ExamOptionItem> options) {
        try {
            return serializeOptions(options);
        } catch (BusinessException invalidOptions) {
            return "\u0000invalid-options";
        }
    }

    private static String normalizeStoredOptions(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            List<ExamPaperDTO.ExamOptionItem> options = OBJECT_MAPPER.readValue(stored,
                    new TypeReference<List<ExamPaperDTO.ExamOptionItem>>() { });
            return safelySerializeOptions(options);
        } catch (Exception ignored) {
            return stored;
        }
    }
}
