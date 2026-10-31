package com.musealgo.oj.judge.metrics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@link OpsMetricsSource} 的 JdbcTemplate 实现（只读聚合查询，OPS-02）。
 *
 * <p>使用 JdbcTemplate 而非其他业务模块的 mapper：一是遵守模块解耦架构约束，
 * 二是监控查询不需要写模型。</p>
 */
@Component
public class JdbcOpsMetricsSource implements OpsMetricsSource {

    private final JdbcTemplate jdbcTemplate;

    public JdbcOpsMetricsSource(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, Object> queryForMap(String sql, Object... args) {
        return jdbcTemplate.queryForMap(sql, args);
    }

    @Override
    public List<Map<String, Object>> queryForList(String sql, Object... args) {
        return jdbcTemplate.queryForList(sql, args);
    }
}
