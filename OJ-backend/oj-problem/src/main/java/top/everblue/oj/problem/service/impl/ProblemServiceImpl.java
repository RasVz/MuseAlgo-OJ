package top.everblue.oj.problem.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import top.everblue.oj.problem.entity.Problem;
import top.everblue.oj.problem.entity.vo.ProblemVO;
import top.everblue.oj.problem.entity.vo.ProblemFilterOptionsVO;
import top.everblue.oj.problem.entity.vo.ProblemTagOptionVO;
import top.everblue.oj.problem.mapper.ProblemMapper;
import top.everblue.oj.problem.service.ProblemService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
                tagNames, source, userStatus, userId);
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
        return problemMapper.selectRandomUnsolvedPublicProblems(userId, finalLimit);
    }
}
