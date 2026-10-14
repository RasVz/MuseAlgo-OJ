-- Backfill problem_tag from the legacy problem.tags JSON column.
-- V4 已经把 problem.tags 改为原生 JSON 列；这里用 JSON_TABLE 展开数组，
-- 按名称精确匹配 tag 字典后写入关联表。
--
-- 说明：仅做精确（区分大小写）匹配；大小写/别名差异（如 "DP" vs "动态规划"）
-- 不在本脚本处理范围，留给一次性 Java Runner（参考 RankInitRunner）做归一化。

INSERT IGNORE INTO problem_tag (problem_id, tag_id)
SELECT p.id, t.id
FROM problem p
JOIN JSON_TABLE(
         p.tags,
         '$[*]' COLUMNS (tagName VARCHAR(64) PATH '$')
     ) AS jt
JOIN tag t
     ON t.name = jt.tagName COLLATE utf8mb4_unicode_ci
WHERE p.tags IS NOT NULL
  AND JSON_LENGTH(p.tags) > 0
  AND (p.is_deleted IS NULL OR p.is_deleted = 0);
