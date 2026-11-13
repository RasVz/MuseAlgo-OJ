package com.musealgo.oj.problem.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.musealgo.oj.api.problem.DraftProblemCommand;
import com.musealgo.oj.api.problem.DraftProblemSample;
import com.musealgo.oj.api.problem.ProblemDraftPublisher;
import com.musealgo.oj.api.problem.SimilarProblemView;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.problem.draft.TitleSimilarity;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.ProblemSample;
import com.musealgo.oj.problem.entity.dto.ProblemDTO;
import com.musealgo.oj.problem.error.ProblemErrorCode;
import com.musealgo.oj.problem.service.ProblemService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 题面草稿落库与相似题提示。
 * <p>
 * 为什么需要这一层：{@code ProblemManagementApplicationService.create} 没有任何入参校验
 * （直接依赖数据库约束），AI 侧一旦给出超长标题或空正文，用户看到的是 500 而不是「哪一项不合格」。
 * 这里在调用前做一次明确校验，把失败变成可读的 400。
 * </p>
 */
@Slf4j
@Component
public class ProblemDraftPublisherImpl implements ProblemDraftPublisher {

    private static final int MAX_TITLE_CODE_POINTS = 128;
    private static final int MAX_TAGS_LENGTH = 250;
    private static final int MAX_SAMPLES = 8;
    private static final int MIN_TIME_LIMIT = 100;
    private static final int MAX_TIME_LIMIT = 30_000;
    private static final int MIN_MEMORY_LIMIT = 16;
    private static final int MAX_MEMORY_LIMIT = 1024;
    /** 标题候选的缓存窗口：题库由教师维护，1 分钟内的新增题目不需要立刻出现在相似提示里。 */
    private static final long CACHE_TTL_MS = 60_000L;
    private static final int CANDIDATE_LIMIT = 5_000;

    private final ProblemManagementApplicationService management;
    private final ProblemService problemService;

    private volatile List<Candidate> candidates = List.of();
    private volatile long candidatesLoadedAt = 0L;

    public ProblemDraftPublisherImpl(ProblemManagementApplicationService management,
                                     ProblemService problemService) {
        this.management = management;
        this.problemService = problemService;
    }

    @Override
    public Long createDraft(Long actorId, DraftProblemCommand command) {
        if (command == null) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "题面内容为空");
        }
        String title = command.title() == null ? "" : command.title().trim();
        if (title.isEmpty()) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "题名为空，无法保存");
        }
        if (title.codePointCount(0, title.length()) > MAX_TITLE_CODE_POINTS) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID,
                    "题名超过 " + MAX_TITLE_CODE_POINTS + " 字上限");
        }
        if (command.content() == null || command.content().isBlank()) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "题目正文为空，无法保存");
        }
        ProblemDTO dto = new ProblemDTO();
        dto.setTitle(title);
        dto.setContent(command.content());
        dto.setInputDesc(nullToEmpty(command.inputDesc()));
        dto.setOutputDesc(nullToEmpty(command.outputDesc()));
        dto.setHint(nullToEmpty(command.hint()));
        dto.setDifficulty(clamp(nvl(command.difficulty(), 1200), 800, 3000));
        dto.setTimeLimit(clamp(nvl(command.timeLimit(), 1000), MIN_TIME_LIMIT, MAX_TIME_LIMIT));
        dto.setMemoryLimit(clamp(nvl(command.memoryLimit(), 512), MIN_MEMORY_LIMIT, MAX_MEMORY_LIMIT));
        dto.setTags(joinTags(command.tags()));
        dto.setSource(truncate(command.source(), 200));
        // AI 生成题面必然没有判题用例：一律私有，公开与否由教师补齐用例后在题库里决定
        dto.setIsPublic(Boolean.FALSE);
        dto.setSamples(toSamples(command.samples()));
        return management.create(dto, actorId);
    }

    @Override
    public List<SimilarProblemView> findSimilar(Long actorId, String title, int limit) {
        if (title == null || title.isBlank()) {
            return List.of();
        }
        int max = Math.max(1, Math.min(10, limit));
        List<Candidate> snapshot = candidates();
        List<SimilarProblemView> hits = new ArrayList<>();
        for (Candidate candidate : snapshot) {
            if (!candidate.visibleTo(actorId)) {
                continue;
            }
            double score = TitleSimilarity.score(title, candidate.title());
            if (score >= TitleSimilarity.DEFAULT_THRESHOLD) {
                hits.add(new SimilarProblemView(candidate.id(), candidate.title(), candidate.difficulty(), score));
            }
        }
        return hits.stream()
                .sorted(Comparator.comparingDouble(SimilarProblemView::score).reversed())
                .limit(max)
                .toList();
    }

    /** 只取标题与可见性所需字段；全量题目正文不参与相似度计算，避免把长文本读进内存。 */
    private List<Candidate> candidates() {
        long now = System.currentTimeMillis();
        if (now - candidatesLoadedAt < CACHE_TTL_MS && !candidates.isEmpty()) {
            return candidates;
        }
        try {
            List<Problem> rows = problemService.list(new LambdaQueryWrapper<Problem>()
                    .select(Problem::getId, Problem::getTitle, Problem::getDifficulty,
                            Problem::getAuthorId, Problem::getIsPublic)
                    .last("LIMIT " + CANDIDATE_LIMIT));
            List<Candidate> loaded = rows.stream()
                    .filter(row -> row.getTitle() != null && !row.getTitle().isBlank())
                    .map(row -> new Candidate(row.getId(), row.getTitle(), row.getDifficulty(),
                            row.getAuthorId(), Boolean.TRUE.equals(row.getIsPublic())))
                    .toList();
            candidates = loaded;
            candidatesLoadedAt = now;
            return loaded;
        } catch (RuntimeException failure) {
            // 相似题只是提示，不能因为查询失败阻断保存主流程
            log.warn("相似题候选加载失败：{}", failure.getMessage());
            return candidates;
        }
    }

    private static List<ProblemSample> toSamples(List<DraftProblemSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }
        List<ProblemSample> rows = new ArrayList<>(Math.min(samples.size(), MAX_SAMPLES));
        for (DraftProblemSample sample : samples) {
            if (sample == null) {
                continue;
            }
            ProblemSample row = new ProblemSample();
            row.setInput(nullToEmpty(sample.input()));
            row.setOutput(nullToEmpty(sample.output()));
            rows.add(row);
            if (rows.size() >= MAX_SAMPLES) {
                break;
            }
        }
        return rows;
    }

    /**
     * 标签按题库既有约定存成逗号分隔串：题目编辑器的标签输入框直接回显该字段，
     * 存 JSON 数组会让教师看到 {@code ["贪心","排序"]}。超出列宽时从尾部丢弃，
     * 保留前几个更能代表解法的标签。
     */
    static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String tag : tags) {
            String cleaned = tag == null ? "" : tag.replaceAll("[\\[\\]\"']", "").trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            String next = text.length() == 0 ? cleaned : text + "," + cleaned;
            if (next.codePointCount(0, next.length()) > MAX_TAGS_LENGTH) {
                break;
            }
            text.setLength(0);
            text.append(next);
        }
        return text.toString();
    }

    private record Candidate(Long id, String title, Integer difficulty, Long authorId, boolean isPublic) {
        boolean visibleTo(Long actorId) {
            return isPublic || (actorId != null && Objects.equals(actorId, authorId));
        }
    }

    private static int nvl(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int maxCodePoints) {
        if (value == null) {
            return null;
        }
        return value.codePointCount(0, value.length()) <= maxCodePoints
                ? value : value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }
}
