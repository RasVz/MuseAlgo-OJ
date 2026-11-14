package com.musealgo.oj.problem.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import com.musealgo.oj.api.contest.ContestProblemAccessQuery;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.vo.ProblemVO;
import com.musealgo.oj.problem.entity.vo.ProblemFilterOptionsVO;
import com.musealgo.oj.problem.entity.vo.ProblemTagOptionVO;
import com.musealgo.oj.problem.mapper.ProblemMapper;
import com.musealgo.oj.problem.service.ProblemService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 题目表 服务实现类
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
@Service
@RequiredArgsConstructor
public class ProblemServiceImpl extends ServiceImpl<ProblemMapper, Problem> implements ProblemService {
    private final ProblemMapper problemMapper;
    private final ContestProblemAccessQuery contestAccess;

    @Override
    public Page<ProblemVO> getProblemVOPage(Integer page, Integer size, String keyword, Integer difficulty,
                                            Integer minDifficulty, Integer maxDifficulty, String tag, String tags,
                                            String source, Integer userStatus, Long userId) {
        Page<ProblemVO> pageParam = new Page<>(page, size);
        List<String> tagNames = splitTags(tags);
        if (tag != null && !tag.isBlank()) {
            tagNames.add(tag.trim());
        }
        return (Page<ProblemVO>) problemMapper.getProblemVOList(
                pageParam, keyword, difficulty, minDifficulty, maxDifficulty,
                tagNames, source, userStatus, userId, hiddenContestProblemIds());
    }

    @Override
    public ProblemFilterOptionsVO getProblemFilterOptions() {
        Map<String, List<String>> tagsByDimension = new LinkedHashMap<>();
        for (ProblemTagOptionVO option : problemMapper.selectPublicKnowledgeTags()) {
            tagsByDimension.computeIfAbsent(option.getDimension(), ignored -> new java.util.ArrayList<>())
                    .add(option.getName());
        }

        LinkedHashSet<String> knownTags = tagsByDimension.values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<String> textTags = problemMapper.selectPublicTagTexts().stream()
                .flatMap(value -> splitTags(value).stream())
                .filter(value -> !knownTags.contains(value))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!textTags.isEmpty()) {
            tagsByDimension.put("OTHER", new java.util.ArrayList<>(textTags));
        }

        ProblemFilterOptionsVO options = new ProblemFilterOptionsVO();
        options.setTagsByDimension(tagsByDimension);
        options.setSources(problemMapper.selectPublicSources());
        return options;
    }

    private List<String> splitTags(String rawTags) {
        if (rawTags == null || rawTags.isBlank()) {
            return new java.util.ArrayList<>();
        }
        return new java.util.ArrayList<>(Arrays.stream(rawTags
                        .replaceAll("[\\[\\]\"]", "")
                        .split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    @Override
    public List<Problem> getRandomUnsolvedPublicProblems(Long userId, Integer limit) {
        int finalLimit = (limit == null || limit <= 0) ? 3 : Math.min(limit, 3);
        return problemMapper.selectRandomUnsolvedPublicProblems(userId, finalLimit, hiddenContestProblemIds());
    }

    /**
     * 比赛进行期需要从题库/搜索/推荐中排除的题目 ID。
     * 教师与管理员保留全量视图用于预览和配置；比赛结束后集合自动为空，题目恢复正常展示。
     */
    @Override
    public List<Long> hiddenContestProblemIds() {
        if (SecurityUtils.isTeacherOrAdmin()) {
            return List.of();
        }
        return contestAccess.findRunningFormalContestProblemIds().stream().toList();
    }

    @Override
    public boolean updateStd(Long problemId, String stdAnswer, String stdExplanation, Integer stdTested) {
        return problemMapper.updateStd(problemId, stdAnswer, stdExplanation, stdTested) > 0;
    }

    @Override
    public int clearStdTested(Long problemId) {
        return problemMapper.clearStdTested(problemId);
    }
}
