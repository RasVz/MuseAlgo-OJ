package top.everblue.oj.community.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 管理员维护的受控标签扩展；内置标签仍由 PostTagCatalog 提供。 */
@Service
@RequiredArgsConstructor
public class CommunityTagService {
    private final JdbcTemplate jdbcTemplate;

    public Map<String, List<String>> enabledOptions() {
        Map<String, List<String>> options = new LinkedHashMap<>();
        jdbcTemplate.queryForList("SELECT name, group_name FROM community_tag WHERE enabled = 1 ORDER BY group_name, name")
                .forEach(row -> options.computeIfAbsent(String.valueOf(row.get("group_name")), ignored -> new java.util.ArrayList<>())
                        .add(String.valueOf(row.get("name"))));
        return options;
    }

    public Set<String> enabledNames() {
        return jdbcTemplate.queryForList("SELECT name FROM community_tag WHERE enabled = 1", String.class)
                .stream().collect(Collectors.toSet());
    }

    public List<Map<String, Object>> all() {
        return jdbcTemplate.queryForList("SELECT id, name, group_name AS groupName, enabled, create_time AS createTime FROM community_tag ORDER BY group_name, name");
    }

    public void save(String name, String groupName, Long creatorId) {
        jdbcTemplate.update("INSERT INTO community_tag (name, group_name, created_by) VALUES (?, ?, ?)", name, groupName, creatorId);
    }

    public void setEnabled(Long id, boolean enabled) {
        jdbcTemplate.update("UPDATE community_tag SET enabled = ? WHERE id = ?", enabled ? 1 : 0, id);
    }
}
