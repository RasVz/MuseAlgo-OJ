-- Knowledge graph for student profiling: a three-level tag taxonomy
-- (dimension -> topic -> tag) plus the problem-tag association that replaces
-- ad-hoc queries over the problem.tags JSON column, and a snapshot table
-- that stores weekly materialized profiles so reports can be served cheaply.

-- ============================================================
-- 1. tag — self-referential taxonomy tree
-- ============================================================
CREATE TABLE IF NOT EXISTS tag (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code        VARCHAR(64)  NOT NULL                  COMMENT '程序标识，如 dp_knapsack；唯一',
    name        VARCHAR(64)  NOT NULL                  COMMENT '展示名，如 背包DP',
    parent_id   BIGINT UNSIGNED NULL                   COMMENT '父标签；NULL=知识域，指向知识域=知识点',
    dimension   VARCHAR(32)  NOT NULL                  COMMENT 'ALGORITHM/DATA_STRUCTURE/MATH/GRAPH/STRING',
    sort_order  INT          NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tag_code (code),
    KEY idx_tag_parent (parent_id),
    KEY idx_tag_dimension (dimension)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='标签字典（三层知识图谱）';

-- ============================================================
-- 2. problem_tag — normalized association (replaces problem.tags JSON for analytics)
-- ============================================================
CREATE TABLE IF NOT EXISTS problem_tag (
    problem_id  BIGINT UNSIGNED NOT NULL,
    tag_id      BIGINT UNSIGNED NOT NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (problem_id, tag_id),
    KEY idx_problem_tag_tag (tag_id)                                       COMMENT '画像统计核心索引：按 tag 反查题目'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='题目-标签关联';

-- ============================================================
-- 3. student_profile_snapshot — weekly materialized profile
-- ============================================================
CREATE TABLE IF NOT EXISTS student_profile_snapshot (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    student_id     BIGINT UNSIGNED NOT NULL,
    class_id       BIGINT UNSIGNED NULL,
    period_start   DATE     NOT NULL                       COMMENT '周期开始（周一）',
    period_end     DATE     NOT NULL                       COMMENT '周期结束（周日）',
    snapshot_json  JSON     NOT NULL                       COMMENT '完整画像 JSON（mastery/percentile/趋势）',
    report_url     VARCHAR(512) NULL                       COMMENT '家长报告 PDF 的 COS 地址',
    create_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_profile_student_period (student_id, period_start),
    KEY idx_profile_class_period (class_id, period_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学生画像周快照';

-- ============================================================
-- 4. seed taxonomy — dimensions and common topics
--    维度（雷达图维度）与高频知识点预置，具体标签由后台或迁移脚本补全
-- ============================================================
INSERT INTO tag (code, name, parent_id, dimension, sort_order) VALUES
    -- 知识域（parent_id = NULL）
    ('algo',   '算法',     NULL, 'ALGORITHM',      1),
    ('ds',     '数据结构', NULL, 'DATA_STRUCTURE', 2),
    ('math',   '数学',     NULL, 'MATH',           3),
    ('graph',  '图论',     NULL, 'GRAPH',          4),
    ('string', '字符串',   NULL, 'STRING',         5);

-- 知识点（parent_id 指向知识域）——用独立 INSERT 保证可引用刚插入的 id
INSERT INTO tag (code, name, parent_id, dimension, sort_order)
SELECT v.code, v.name, t.id, v.dimension, v.sort_order
FROM tag t
JOIN (
    SELECT 'dp'        AS code, '动态规划' AS name, 'algo'  AS parent_code, 'ALGORITHM'      AS dimension, 1 AS sort_order UNION ALL
    SELECT 'greedy',          '贪心',              'algo',  'ALGORITHM',      2 UNION ALL
    SELECT 'search',          '搜索',              'algo',  'ALGORITHM',      3 UNION ALL
    SELECT 'divide_conquer',  '分治',              'algo',  'ALGORITHM',      4 UNION ALL
    SELECT 'tree',            '树',                'ds',    'DATA_STRUCTURE', 1 UNION ALL
    SELECT 'array',           '数组',              'ds',    'DATA_STRUCTURE', 2 UNION ALL
    SELECT 'stack_queue',     '栈与队列',          'ds',    'DATA_STRUCTURE', 3 UNION ALL
    SELECT 'number_theory',   '数论',              'math',  'MATH',           1 UNION ALL
    SELECT 'combinatorics',   '组合数学',          'math',  'MATH',           2 UNION ALL
    SELECT 'shortest_path',   '最短路',            'graph', 'GRAPH',          1 UNION ALL
    SELECT 'mst',             '最小生成树',        'graph', 'GRAPH',          2 UNION ALL
    SELECT 'string_match',    '字符串匹配',        'string','STRING',         1
) v ON t.code = v.parent_code AND t.parent_id IS NULL;
