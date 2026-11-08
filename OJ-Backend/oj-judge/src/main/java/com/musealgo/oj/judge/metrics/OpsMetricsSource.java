package com.musealgo.oj.judge.metrics;

import java.util.List;
import java.util.Map;

/**
 * 只读 SQL 查询入口（OPS-02）。独立接口便于离线单测，不在 metrics 包内直接依赖业务模块 mapper。
 */
public interface OpsMetricsSource {

    /** 执行返回单行的聚合查询；参数使用 JDBC 占位符（?）。 */
    Map<String, Object> queryForMap(String sql, Object... args);

    /** 执行返回多行的聚合查询（如按状态分组的计数）。 */
    List<Map<String, Object>> queryForList(String sql, Object... args);
}
