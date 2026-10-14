package top.everblue.oj.problem.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.model.SearchResult;
import com.meilisearch.sdk.model.Searchable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.everblue.oj.problem.entity.Problem;
import top.everblue.oj.problem.service.ProblemService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 全文搜索索引组件（2026-08-14 方案第 8 节，任务⑧）。
 *
 * <p>职责：
 * <ul>
 *   <li><b>写侧同步</b>：题目新增/更新/删除时同步索引（{@link #indexProblem}/{@link #deleteProblem}），
 *       失败仅 WARN + 计数，绝不阻塞业务主流程；</li>
 *   <li><b>兜底重建</b>：{@code @Scheduled} 每日全量重建 + 管理端点手动触发（{@link #reindexAll}）；</li>
 *   <li><b>读侧召回</b>：{@link #searchIds} 返回命中 id 集合，由 application 层回查 DB 详情。</li>
 * </ul>
 *
 * <p>故障隔离：简单断路器——连续失败 {@code failure-threshold} 次后短路
 * {@code cooldown-ms} 毫秒，期间 {@link #searchIds} 直接抛出，调用方自动回退 LIKE 查询。
 */
@Component
public class ProblemSearchIndexer {

    private static final Logger log = LoggerFactory.getLogger(ProblemSearchIndexer.class);

    /** Meilisearch 索引 UID */
    public static final String INDEX_UID = "problems";

    /** 可搜索字段：标题 / 标签 / 数字题号 */
    private static final String[] SEARCHABLE_ATTRIBUTES = {"title", "tags", "id"};

    /** 可过滤字段：难度 / 是否公开（filter 语法依赖） */
    private static final String[] FILTERABLE_ATTRIBUTES = {"difficulty", "isPublic"};

    /** 全量重建分批提交大小 */
    private static final int REINDEX_BATCH_SIZE = 200;

    private final ProblemService problemService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final boolean enabled;
    private final String meiliUrl;
    private final String meiliApiKey;
    private final int failureThreshold;
    private final long cooldownMs;

    /** 惰性创建：enabled=false 或从未触发写操作时不建连 */
    private volatile Client client;

    /** 索引 settings 是否已配置（searchable/filterableAttributes，幂等） */
    private final AtomicBoolean settingsConfigured = new AtomicBoolean(false);

    /** 断路器状态 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile long circuitOpenUntilMs = 0L;

    public ProblemSearchIndexer(ProblemService problemService,
                                JdbcTemplate jdbcTemplate,
                                ObjectMapper objectMapper,
                                @Value("${oj.search.enabled:true}") boolean enabled,
                                @Value("${oj.search.meilisearch.url:http://localhost:7700}") String meiliUrl,
                                @Value("${oj.search.meilisearch.api-key:}") String meiliApiKey,
                                @Value("${oj.search.breaker.failure-threshold:3}") int failureThreshold,
                                @Value("${oj.search.breaker.cooldown-ms:30000}") long cooldownMs) {
        this.problemService = problemService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.meiliUrl = meiliUrl;
        this.meiliApiKey = meiliApiKey;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownMs = Math.max(1000, cooldownMs);
    }

    // ==================== 写侧 ====================

    /**
     * 新增或更新单题索引（Meilisearch upsert，按 id 幂等）。
     * 任何失败仅 WARN + 计数，不向调用方抛出。
     */
    public void indexProblem(Problem problem) {
        if (!active() || problem == null || problem.getId() == null) {
            return;
        }
        try {
            ensureSettings();
            String documentJson = toDocumentJson(problem);
            meili().index(INDEX_UID).updateDocuments(documentJson);
            resetFailures();
            log.debug("题目索引已同步 problemId={}", problem.getId());
        } catch (Exception e) {
            onFailure("题目索引同步失败 problemId={}", problem.getId(), e);
        }
    }

    /**
     * 删除单题索引。失败仅 WARN + 计数，不抛异常。
     */
    public void deleteProblem(Long problemId) {
        if (!active() || problemId == null) {
            return;
        }
        try {
            meili().index(INDEX_UID).deleteDocument(String.valueOf(problemId));
            resetFailures();
            log.debug("题目索引已删除 problemId={}", problemId);
        } catch (Exception e) {
            onFailure("题目索引删除失败 problemId={}", problemId, e);
        }
    }

    /**
     * 全量重建索引：分页查询全部未删除题目，分批提交。
     * 供每日定时任务与管理端点共用；失败仅 WARN，不抛异常。
     *
     * @return 本次重建统计（成功提交 / 总题目数）
     */
    public ReindexResult reindexAll() {
        if (!enabled) {
            return new ReindexResult(0, 0, "search disabled");
        }
        int indexed = 0;
        long total = 0;
        try {
            ensureSettings();
            Client c = meili();
            // 先清空再重建，避免已删除题目残留
            c.index(INDEX_UID).deleteAllDocuments();
            int current = 1;
            while (true) {
                Page<Problem> batchPage = problemService.page(new Page<>(current, REINDEX_BATCH_SIZE),
                        new LambdaQueryWrapper<Problem>().eq(Problem::getIsDeleted, 0));
                List<Problem> batch = batchPage.getRecords();
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                total = batchPage.getTotal();
                submitBatch(c, batch);
                indexed += batch.size();
                if (batch.size() < REINDEX_BATCH_SIZE || current * REINDEX_BATCH_SIZE >= total) {
                    break;
                }
                current++;
            }
            resetFailures();
            log.info("搜索索引全量重建完成：indexed={}, total={}", indexed, total);
        } catch (Exception e) {
            onFailure("搜索索引全量重建失败", null, e);
        }
        return new ReindexResult(indexed, total, "ok");
    }

    /** 每日全量重建兜底（凌晨 4:10 低峰，cron 可配置） */
    @Scheduled(cron = "${oj.search.reindex-cron:0 10 4 * * *}")
    public void scheduledDailyReindex() {
        if (!enabled) {
            return;
        }
        log.info("触发每日搜索索引全量重建");
        reindexAll();
    }

    // ==================== 读侧 ====================

    /**
     * 搜索命中 id 集合（按 Meilisearch 相关性排序）。
     *
     * @param difficulty     难度精确过滤（可选；与 min/max 互斥优先）
     * @param minDifficulty  难度下限（可选，闭区间）
     * @param maxDifficulty  难度上限（可选，闭区间）
     * @throws IllegalStateException 未启用 / 熔断中 / Meilisearch 调用失败（原始异常包装为 cause），
     *                               调用方应捕获并回退 LIKE 查询
     */
    public SearchHits searchIds(String keyword, int page, int size,
                                Integer difficulty, Integer minDifficulty, Integer maxDifficulty) {
        if (!active()) {
            throw new IllegalStateException("meilisearch search disabled");
        }
        if (circuitOpen()) {
            throw new IllegalStateException("meilisearch circuit open");
        }
        try {
            SearchRequest request = new SearchRequest(keyword == null ? "" : keyword.trim())
                    .setPage(Math.max(1, page))
                    .setHitsPerPage(Math.max(1, size));
            request.setFilter(new String[]{"isPublic = true AND " + difficultyFilter(difficulty, minDifficulty, maxDifficulty)});
            Searchable searchable = meili().index(INDEX_UID).search(request);
            if (!(searchable instanceof SearchResult result)) {
                return new SearchHits(List.of(), 0);
            }
            List<Long> ids = result.getHits().stream()
                    .map(hit -> hit.get("id"))
                    .filter(Objects::nonNull)
                    .map(value -> Long.valueOf(value.toString()))
                    .toList();
            resetFailures();
            return new SearchHits(ids, result.getEstimatedTotalHits());
        } catch (Exception e) {
            onFailure("搜索索引查询失败 keyword={}", keyword, e);
            throw new IllegalStateException("meilisearch query failed", e);
        }
    }

    /** 组装难度 filter 表达式（Meilisearch 比较运算对数字字段直接生效） */
    private static String difficultyFilter(Integer difficulty, Integer minDifficulty, Integer maxDifficulty) {
        if (difficulty != null) {
            return "difficulty = " + difficulty;
        }
        List<String> parts = new ArrayList<>(2);
        if (minDifficulty != null) {
            parts.add("difficulty >= " + minDifficulty);
        }
        if (maxDifficulty != null) {
            parts.add("difficulty <= " + maxDifficulty);
        }
        return parts.isEmpty() ? "difficulty >= 0" : String.join(" AND ", parts);
    }

    /** 搜索是否可用（配置开关 + 服务地址） */
    public boolean active() {
        return enabled && meiliUrl != null && !meiliUrl.isBlank();
    }

    /** 熔断是否处于打开状态 */
    public boolean circuitOpen() {
        return System.currentTimeMillis() < circuitOpenUntilMs;
    }

    /** 连续失败计数（测试断言用） */
    public int failureCount() {
        return consecutiveFailures.get();
    }

    // ==================== 内部实现 ====================

    private void submitBatch(Client c, List<Problem> batch) throws Exception {
        Map<Long, List<String>> domains = loadKnowledgeDomains(batch.stream().map(Problem::getId).toList());
        List<String> docs = new ArrayList<>(batch.size());
        for (Problem p : batch) {
            docs.add(toDocumentJson(p, domains.getOrDefault(p.getId(), List.of())));
        }
        String documents = "[" + String.join(",", docs) + "]";
        c.index(INDEX_UID).addDocuments(documents);
    }

    private String toDocumentJson(Problem problem) {
        return toDocumentJson(problem, loadKnowledgeDomains(List.of(problem.getId())).getOrDefault(problem.getId(), List.of()));
    }

    /** 组装索引文档 JSON（id/title/tags/difficulty/source/knowledgeDomain/isPublic/updatedAt） */
    private String toDocumentJson(Problem problem, List<String> knowledgeDomains) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", problem.getId());
        doc.put("title", nullToEmpty(problem.getTitle()));
        doc.put("tags", parseTags(problem.getTags()));
        doc.put("difficulty", problem.getDifficulty() == null ? 0 : problem.getDifficulty());
        doc.put("source", nullToEmpty(problem.getSource()));
        doc.put("knowledgeDomain", knowledgeDomains);
        doc.put("isPublic", Boolean.TRUE.equals(problem.getIsPublic()));
        doc.put("updatedAt", problem.getUpdateTime() != null ? problem.getUpdateTime().toString()
                : (problem.getCreateTime() != null ? problem.getCreateTime().toString() : ""));
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (Exception e) {
            // 序列化失败兜底：手工拼接（字段均来自实体，无嵌套转义风险之外的场景）
            return "{\"id\":" + problem.getId() + ",\"title\":\"\"}";
        }
    }

    /** 解析题目 tags JSON 字符串（形如 ["DP","Array"]），解析失败按逗号拆分兜底 */
    private List<String> parseTags(String rawTags) {
        if (rawTags == null || rawTags.isBlank()) {
            return List.of();
        }
        try {
            List<String> tags = objectMapper.readValue(rawTags,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    });
            return tags == null ? List.of() : tags.stream().filter(Objects::nonNull).toList();
        } catch (Exception e) {
            // 逗号分隔的兜底拆分
            return java.util.Arrays.stream(rawTags.replaceAll("[\\[\\]\"\\s]", "")
                            .split(","))
                    .filter(t -> !t.isBlank())
                    .toList();
        }
    }

    /** 批量查询知识域标签（problem_tag 关联 tag 表中 dimension 非空的标签名） */
    private Map<Long, List<String>> loadKnowledgeDomains(Collection<Long> problemIds) {
        if (problemIds == null || problemIds.isEmpty()) {
            return Map.of();
        }
        try {
            String placeholders = problemIds.stream().map(id -> "?").collect(Collectors.joining(","));
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT pt.problem_id AS pid, t.name AS name FROM problem_tag pt "
                            + "INNER JOIN tag t ON t.id = pt.tag_id "
                            + "WHERE pt.problem_id IN (" + placeholders + ") "
                            + "AND t.parent_id IS NOT NULL AND t.dimension IS NOT NULL AND t.dimension <> ''",
                    problemIds.toArray());
            Map<Long, List<String>> result = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                if (row.get("pid") == null || row.get("name") == null) {
                    continue;
                }
                result.computeIfAbsent(Long.valueOf(row.get("pid").toString()), ignored -> new ArrayList<>())
                        .add(row.get("name").toString());
            }
            return result;
        } catch (Exception e) {
            log.warn("知识域标签查询失败，索引文档将不含 knowledgeDomain：{}", e.getMessage());
            return Map.of();
        }
    }

    /** 配置 searchable/filterable attributes（幂等，仅首次执行；失败复位以便下次重试） */
    private void ensureSettings() throws Exception {
        if (!settingsConfigured.compareAndSet(false, true)) {
            return;
        }
        try {
            Index index = meili().index(INDEX_UID);
            index.updateSearchableAttributesSettings(SEARCHABLE_ATTRIBUTES);
            index.updateFilterableAttributesSettings(FILTERABLE_ATTRIBUTES);
        } catch (Exception e) {
            settingsConfigured.set(false);
            throw e;
        }
    }

    /** 懒加载 Meilisearch Client；apiKey 为空时使用无鉴权 Config（本地 MEILI_ENV=development 场景） */
    private Client meili() {
        Client current = client;
        if (current == null) {
            synchronized (this) {
                if (client == null) {
                    Config config = (meiliApiKey == null || meiliApiKey.isBlank())
                            ? new Config(meiliUrl)
                            : new Config(meiliUrl, meiliApiKey);
                    client = new Client(config);
                }
                current = client;
            }
        }
        return current;
    }

    /** 测试注入点：替换真实 Client（Mockito mock），包可见 */
    void setClientForTesting(Client mockClient) {
        this.client = mockClient;
        this.settingsConfigured.set(true);
    }

    private void onFailure(String message, Object arg, Exception e) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            circuitOpenUntilMs = System.currentTimeMillis() + cooldownMs;
            log.warn(message + "（连续失败 {} 次，熔断 {}ms）", arg, failures, cooldownMs);
        } else {
            log.warn(message + "（失败计数 {}）", arg, failures);
        }
    }

    private void resetFailures() {
        consecutiveFailures.set(0);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 搜索召回结果：命中 id（按相关性排序）+ 估算总量 */
    public record SearchHits(List<Long> ids, long total) {
    }

    /** 全量重建结果统计 */
    public record ReindexResult(long indexed, long total, String status) {
    }
}
