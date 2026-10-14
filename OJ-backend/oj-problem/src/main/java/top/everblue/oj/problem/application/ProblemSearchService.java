package top.everblue.oj.problem.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.everblue.oj.problem.entity.Problem;
import top.everblue.oj.problem.entity.vo.ProblemVO;
import top.everblue.oj.problem.infrastructure.ProblemSearchIndexer;
import top.everblue.oj.problem.infrastructure.ProblemSearchIndexer.SearchHits;
import top.everblue.oj.problem.mapper.ProblemMapper;
import top.everblue.oj.problem.service.ProblemService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 全文搜索读侧编排（2026-08-14 方案第 8 节，任务⑧）。
 *
 * <p>流程：Meilisearch 召回命中 id 集合 → DB 回查详情（复用 {@link ProblemVO} 结构）→ 按相关性排序返回。
 * <p>降级：Meilisearch 未启用 / 熔断 / 调用异常（连接失败、超时）时自动回退现有
 * {@code ProblemMapper} 的 LIKE 查询，保证搜索接口永不 5xx。
 */
@Service
public class ProblemSearchService {

    private static final Logger log = LoggerFactory.getLogger(ProblemSearchService.class);

    private final ProblemSearchIndexer searchIndexer;
    private final ProblemService problemService;
    private final ProblemMapper problemMapper;

    public ProblemSearchService(ProblemSearchIndexer searchIndexer,
                                ProblemService problemService,
                                ProblemMapper problemMapper) {
        this.searchIndexer = searchIndexer;
        this.problemService = problemService;
        this.problemMapper = problemMapper;
    }

    /**
     * 公开搜索（关键词 + 难度过滤）。
     *
     * @param keyword       搜索关键词（可为空：空关键词直接走 LIKE 全量，行为与 /problem/list 一致）
     * @param difficulty    难度精确过滤（可选，与 min/max 互斥优先）
     * @param minDifficulty 难度下限（可选，闭区间）
     * @param maxDifficulty 难度上限（可选，闭区间）
     * @param userId        当前登录用户（可为 null，用于计算 userStatus）
     */
    public Page<ProblemVO> search(String keyword, Integer page, Integer size, Integer difficulty,
                                  Integer minDifficulty, Integer maxDifficulty, Long userId) {
        int safePage = page == null || page <= 0 ? 1 : page;
        int safeSize = size == null || size <= 0 ? 20 : size;
        if (!StringUtils.hasText(keyword)) {
            return likeFallback(keyword, safePage, safeSize, difficulty, minDifficulty, maxDifficulty, userId);
        }
        try {
            SearchHits hits = searchIndexer.searchIds(keyword, safePage, safeSize, difficulty, minDifficulty, maxDifficulty);
            if (hits.ids().isEmpty()) {
                Page<ProblemVO> empty = new Page<>(safePage, safeSize, 0);
                empty.setRecords(List.of());
                return empty;
            }
            return buildFromHits(hits, safePage, safeSize, userId);
        } catch (Exception e) {
            // Meilisearch 异常（含熔断）→ 自动回退 LIKE，接口永不 5xx
            log.warn("Meilisearch 搜索不可用，回退 LIKE 查询：{}", e.getMessage());
            return likeFallback(keyword, safePage, safeSize, difficulty, minDifficulty, maxDifficulty, userId);
        }
    }

    /**
     * 全量重建搜索索引（管理端点触发，委托基础设施层）。
     */
    public ProblemSearchIndexer.ReindexResult reindexAll() {
        return searchIndexer.reindexAll();
    }

    /** 索引组件是否启用（降级状态观测用） */
    public boolean searchIndexEnabled() {
        return searchIndexer.active();
    }

    private Page<ProblemVO> buildFromHits(SearchHits hits, int page, int size, Long userId) {
        List<Long> ids = hits.ids();
        Map<Long, Problem> byId = problemService.listByIds(ids).stream()
                .collect(Collectors.toMap(Problem::getId, Function.identity(), (a, b) -> a));
        Map<Long, Integer> statusMap = loadUserStatus(userId, ids);

        List<ProblemVO> records = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Problem problem = byId.get(id);
            // DB 兜底过滤：Meilisearch 只索引 isPublic=true，回查时再次确认公开与未删除
            if (problem == null || !Boolean.TRUE.equals(problem.getIsPublic())) {
                continue;
            }
            ProblemVO vo = new ProblemVO();
            org.springframework.beans.BeanUtils.copyProperties(problem, vo);
            vo.setUserStatus(statusMap.getOrDefault(id, 0));
            records.add(vo);
        }

        Page<ProblemVO> result = new Page<>(page, size, hits.total());
        result.setRecords(records);
        return result;
    }

    /** 批量计算用户做题状态（0 未开始 / 1 已通过 / 2 尝试过），未登录一律 0 */
    private Map<Long, Integer> loadUserStatus(Long userId, List<Long> problemIds) {
        if (userId == null || problemIds.isEmpty()) {
            return Map.of();
        }
        try {
            return problemMapper.selectUserStatusByProblemIds(userId, problemIds).stream()
                    .filter(row -> row.get("problemId") != null && row.get("userStatus") != null)
                    .collect(Collectors.toMap(
                            row -> Long.valueOf(row.get("problemId").toString()),
                            row -> Integer.valueOf(row.get("userStatus").toString())));
        } catch (Exception e) {
            log.warn("做题状态回查失败，userStatus 按未开始处理：{}", e.getMessage());
            return Map.of();
        }
    }

    /** LIKE 降级：复用现有 getProblemVOList SQL（含 userStatus 计算与公开过滤） */
    private Page<ProblemVO> likeFallback(String keyword, int page, int size, Integer difficulty,
                                         Integer minDifficulty, Integer maxDifficulty, Long userId) {
        return problemService.getProblemVOPage(page, size, keyword, difficulty,
                minDifficulty, maxDifficulty, null, null, null, null, userId);
    }
}
