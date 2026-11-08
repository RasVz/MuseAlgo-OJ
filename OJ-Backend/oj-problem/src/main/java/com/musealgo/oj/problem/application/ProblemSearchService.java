package com.musealgo.oj.problem.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.vo.ProblemVO;
import com.musealgo.oj.problem.infrastructure.ProblemSearchIndexer;
import com.musealgo.oj.problem.infrastructure.ProblemSearchIndexer.SearchHits;
import com.musealgo.oj.problem.mapper.ProblemMapper;
import com.musealgo.oj.problem.service.ProblemService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private static final int MAX_SEARCH_CANDIDATES = 1000;

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
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<ProblemVO> search(String keyword, Integer page, Integer size, Integer difficulty,
                                  Integer minDifficulty, Integer maxDifficulty, Long userId) {
        int safePage = page == null || page <= 0 ? 1 : page;
        int safeSize = size == null || size <= 0 ? 20 : size;
        if (!StringUtils.hasText(keyword)) {
            return likeFallback(keyword, safePage, safeSize, difficulty, minDifficulty, maxDifficulty, userId);
        }
        try {
            // Filter the complete bounded candidate window before counting or paging. Filtering only
            // the requested page leaves hidden hits in the total and creates holes on other pages.
            SearchHits hits = searchIndexer.searchIds(keyword, 1, MAX_SEARCH_CANDIDATES, difficulty, minDifficulty, maxDifficulty);
            if (!hits.complete() || hits.total() > MAX_SEARCH_CANDIDATES || hits.ids().size() != hits.total()) {
                throw new IllegalStateException("search candidate window exceeds budget or is incomplete");
            }
            if (hits.ids().isEmpty()) {
                Page<ProblemVO> empty = new Page<>(safePage, safeSize, hits.total());
                empty.setRecords(List.of());
                return empty;
            }
            var visibleIds = new HashSet<>(problemMapper.selectVisibleSearchIds(hits.ids(), difficulty, minDifficulty, maxDifficulty));
            List<Long> orderedIds = hits.ids().stream().filter(visibleIds::contains).distinct().toList();
            int from = (int) Math.min((long) (safePage - 1) * safeSize, orderedIds.size());
            int to = (int) Math.min((long) from + safeSize, orderedIds.size());
            List<Long> pageIds = orderedIds.subList(from, to);
            if (pageIds.isEmpty()) {
                Page<ProblemVO> empty = new Page<>(safePage, safeSize, orderedIds.size());
                empty.setRecords(List.of());
                return empty;
            }
            return buildFromHits(new SearchHits(pageIds, orderedIds.size()), safePage, safeSize, userId);
        } catch (Exception e) {
            // Meilisearch 异常（含熔断）→ 自动回退 LIKE，接口永不 5xx
            log.warn("全文搜索未完成，回退 LIKE 查询：{}", e.getMessage());
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
