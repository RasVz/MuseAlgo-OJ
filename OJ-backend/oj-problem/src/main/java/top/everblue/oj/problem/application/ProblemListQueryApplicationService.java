package top.everblue.oj.problem.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.everblue.oj.problem.entity.Problem;
import top.everblue.oj.problem.service.ProblemService;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 题库列表查询（我的题目 / 管理员全量），含动态列名兼容（历史库字段差异）。
 * <p>
 * 从 {@link ProblemManagementApplicationService} 拆出（2026-08-15 大服务类拆分）。
 * </p>
 */
@Service
public class ProblemListQueryApplicationService {

    private final ProblemService problemService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public ProblemListQueryApplicationService(ProblemService problemService,
                                              ObjectMapper objectMapper,
                                              JdbcTemplate jdbcTemplate) {
        this.problemService = problemService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 我的题目列表（限创建人，含私有题） */
    public Page<Map<String, Object>> getMyCreatedProblemList(
            Long userId, Integer page, Integer size, String keyword, Integer difficulty,
            String tag, Integer privacy, Boolean isPublic, String sortBy, String sortOrder) {
        return queryProblemList(userId, page, size, keyword, difficulty, tag, privacy, isPublic, sortBy, sortOrder);
    }

    /** 管理员全量题库列表（不限创建人，含私有题） */
    public Page<Map<String, Object>> adminList(
            Integer page, Integer size, String keyword, Integer difficulty,
            String tag, Integer privacy, Boolean isPublic, String sortBy, String sortOrder) {
        return queryProblemList(null, page, size, keyword, difficulty, tag, privacy, isPublic, sortBy, sortOrder);
    }

    private Page<Map<String, Object>> queryProblemList(
            Long creatorId, Integer page, Integer size, String keyword, Integer difficulty,
            String tag, Integer privacy, Boolean isPublic, String sortBy, String sortOrder) {
        Set<String> cols = getTableColumns("problem");
        String creatorCol = pickColumn(cols, "author_id", "creator_id", "user_id");
        String titleCol = pickColumn(cols, "title");
        String tagsCol = pickColumn(cols, "tags");
        String idCol = pickColumn(cols, "id");
        String diffCol = pickColumn(cols, "difficulty");
        String timeCol = pickColumn(cols, "create_time");
        String publicCol = pickColumn(cols, "is_public", "isPublic", "privacy");
        if (!StringUtils.hasText(creatorCol)) throw new IllegalStateException("缺少创建人字段");

        QueryWrapper<Problem> w = new QueryWrapper<>();
        if (creatorId != null) {
            w.eq(creatorCol, creatorId);
        }

        Integer pf = privacy != null && (privacy == 0 || privacy == 1) ? privacy
                : (isPublic != null ? (isPublic ? 1 : 0) : null);
        if (pf != null && StringUtils.hasText(publicCol)) w.eq(publicCol, pf);

        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            w.and(q -> {
                boolean a = false;
                if (StringUtils.hasText(titleCol)) { q.like(titleCol, kw); a = true; }
                if (StringUtils.hasText(tagsCol)) { if (a) q.or(); q.like(tagsCol, kw); a = true; }
                if (isNumeric(kw) && StringUtils.hasText(idCol)) { if (a) q.or(); q.eq(idCol, Long.parseLong(kw)); }
            });
        }
        if (difficulty != null && StringUtils.hasText(diffCol)) w.eq(diffCol, difficulty);
        if (StringUtils.hasText(tag) && StringUtils.hasText(tagsCol)) w.like(tagsCol, tag.trim());
        boolean ascending = "ascending".equalsIgnoreCase(sortOrder);
        if ("id".equals(sortBy) && StringUtils.hasText(idCol)) {
            w.orderBy(true, ascending, idCol);
        } else if (StringUtils.hasText(timeCol)) {
            w.orderByDesc(timeCol);
        } else if (StringUtils.hasText(idCol)) {
            w.orderByDesc(idCol);
        }

        Page<Problem> dp = problemService.page(new Page<>(page, size), w);
        List<Map<String, Object>> records = dp.getRecords().stream().map(p -> {
            Map<String, Object> item = objectMapper.convertValue(p, new TypeReference<Map<String, Object>>() {});
            item.put("testCaseCount", p.getTestCaseCount() == null ? 0 : p.getTestCaseCount());
            item.put("isPublic", toBool(item.get("isPublic") != null ? item.get("isPublic") : item.get("privacy")));
            return item;
        }).collect(Collectors.toList());

        Page<Map<String, Object>> rp = new Page<>(dp.getCurrent(), dp.getSize(), dp.getTotal());
        rp.setRecords(records);
        return rp;
    }

    private Set<String> getTableColumns(String t) {
        try {
            Set<String> s = new HashSet<>();
            for (Map<String, Object> r : jdbcTemplate.queryForList("SHOW COLUMNS FROM " + t))
                if (r.get("Field") != null) s.add(r.get("Field").toString());
            return s;
        } catch (Exception e) { return Collections.emptySet(); }
    }

    private String pickColumn(Set<String> cs, String... cands) {
        if (cs == null || cs.isEmpty()) return null;
        for (String c : cands) if (cs.contains(c)) return c;
        return null;
    }

    private boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() == 1;
        String s = String.valueOf(v).trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    private boolean isNumeric(String s) {
        if (!StringUtils.hasText(s)) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }
}
