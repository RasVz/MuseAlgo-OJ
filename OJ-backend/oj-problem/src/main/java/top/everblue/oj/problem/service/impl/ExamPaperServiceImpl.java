package top.everblue.oj.problem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import top.everblue.oj.problem.application.ExamAttemptApplicationService;
import top.everblue.oj.problem.application.ExamPaperStatsApplicationService;
import top.everblue.oj.problem.domain.ObjectiveJudge;
import top.everblue.oj.problem.entity.ExamAttempt;
import top.everblue.oj.problem.entity.ExamAnswer;
import top.everblue.oj.problem.entity.ExamPaper;
import top.everblue.oj.problem.entity.ExamPaperProblem;
import top.everblue.oj.problem.entity.ExamQuestion;
import top.everblue.oj.problem.entity.Problem;
import top.everblue.oj.problem.entity.dto.ExamAnswerSubmitDTO;
import top.everblue.oj.problem.entity.dto.ExamPaperDTO;
import top.everblue.oj.problem.entity.vo.ExamAttemptVO;
import top.everblue.oj.problem.entity.vo.ExamPaperVO;
import top.everblue.oj.problem.entity.vo.ExamPaperStatsVO;
import top.everblue.oj.problem.mapper.ExamAttemptMapper;
import top.everblue.oj.problem.mapper.ExamAnswerMapper;
import top.everblue.oj.problem.mapper.ExamPaperMapper;
import top.everblue.oj.problem.mapper.ExamPaperProblemMapper;
import top.everblue.oj.problem.mapper.ExamQuestionMapper;
import top.everblue.oj.problem.mapper.ProblemMapper;
import top.everblue.oj.problem.service.ExamPaperService;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
        wrapper.eq(StringUtils.hasText(category), ExamPaper::getCategory, category)
                .eq(Boolean.TRUE.equals(publishedOnly), ExamPaper::getIsPublished, 1)
                .like(StringUtils.hasText(keyword), ExamPaper::getTitle, keyword)
                .orderByDesc(ExamPaper::getUpdateTime);
        return examPaperMapper.selectPage(new Page<>(page == null ? 1 : page, size == null ? 10 : size), wrapper);
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
        vo.setCreateTime(paper.getCreateTime());
        vo.setUpdateTime(paper.getUpdateTime());

        vo.setProblems(buildProblems(id));
        vo.setQuestions(buildQuestions(id, withAnswers));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long savePaper(ExamPaperDTO dto, Long createBy) {
        ExamPaper paper = dto.getId() == null ? new ExamPaper() : examPaperMapper.selectById(dto.getId());
        if (paper == null) {
            throw new IllegalArgumentException("试卷不存在: " + dto.getId());
        }
        paper.setTitle(dto.getTitle());
        paper.setDescription(dto.getDescription());
        paper.setCategory(dto.getCategory());
        paper.setLevel(dto.getLevel());
        paper.setDurationMinutes(dto.getDurationMinutes());
        paper.setTotalScore(dto.getTotalScore());
        paper.setIsPublished(dto.getIsPublished() == null ? 0 : dto.getIsPublished());
        paper.setProblemCount(dto.getProblems() == null ? 0 : dto.getProblems().size());
        LocalDateTime now = LocalDateTime.now();
        if (paper.getId() == null) {
            paper.setCreateBy(createBy);
            paper.setCreateTime(now);
            paper.setUpdateTime(now);
            examPaperMapper.insert(paper);
        } else {
            paper.setUpdateTime(now);
            examPaperMapper.updateById(paper);
            examPaperProblemMapper.delete(
                    new LambdaQueryWrapper<ExamPaperProblem>().eq(ExamPaperProblem::getPaperId, paper.getId()));
        }

        // 编程题（题单式关联）
        if (dto.getProblems() != null) {
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

        // 客观题：整组重建（旧作答随题目变更失效）
        examQuestionMapper.delete(
                new LambdaQueryWrapper<ExamQuestion>().eq(ExamQuestion::getPaperId, paper.getId()));
        examAnswerMapper.delete(
                new LambdaQueryWrapper<ExamAnswer>().eq(ExamAnswer::getPaperId, paper.getId()));
        examAttemptMapper.delete(
                new LambdaQueryWrapper<ExamAttempt>().eq(ExamAttempt::getPaperId, paper.getId()));
        if (dto.getQuestions() != null) {
            int qOrder = 1;
            for (ExamPaperDTO.ExamQuestionItem item : dto.getQuestions()) {
                String normalizedAnswer = ObjectiveJudge.normalize(item.getQuestionType(), item.getAnswer());
                if (normalizedAnswer == null) {
                    throw new IllegalArgumentException("第 " + qOrder + " 题的标准答案不合法: " + item.getAnswer());
                }
                ExamQuestion question = new ExamQuestion();
                question.setPaperId(paper.getId());
                question.setQuestionType(item.getQuestionType());
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
        examPaperMapper.deleteById(id); // @TableLogic 逻辑删除
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
            throw new IllegalArgumentException("试卷不存在: " + paperId);
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
            if (problem == null) {
                continue;
            }
            ExamPaperVO.ExamPaperProblemVO item = new ExamPaperVO.ExamPaperProblemVO();
            item.setProblemId(problem.getId());
            item.setProblemOrder(relation.getProblemOrder());
            item.setScore(relation.getScore());
            item.setTitle(problem.getTitle());
            item.setDifficulty(problem.getDifficulty());
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
            return OBJECT_MAPPER.writeValueAsString(options);
        } catch (Exception e) {
            throw new IllegalArgumentException("选项格式不合法", e);
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
}
