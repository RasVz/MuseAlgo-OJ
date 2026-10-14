-- V9: 将「动态规划」从算法维度独立为第六知识域 DP
-- 雷达图从五维扩展为六维：数据结构、算法基础、动态规划、字符串、图论、数学

-- 1. 新增 DP 知识域
INSERT INTO tag (code, name, parent_id, dimension, sort_order)
VALUES ('dp_dim', '动态规划', NULL, 'DP', 3)
ON DUPLICATE KEY UPDATE name = name;

-- 2. 更新排序：原来的 MATH/GRAPH/STRING 往后移
UPDATE tag SET sort_order = 4, dimension = 'MATH' WHERE code = 'math' AND parent_id IS NULL;
UPDATE tag SET sort_order = 5, dimension = 'GRAPH' WHERE code = 'graph' AND parent_id IS NULL;
UPDATE tag SET sort_order = 6, dimension = 'STRING' WHERE code = 'string' AND parent_id IS NULL;

-- 3. 将动态规划知识点从 ALGORITHM 迁移到 DP 维度（仅当 DP 知识域存在时执行）
UPDATE tag SET parent_id = (SELECT id FROM (SELECT id FROM tag WHERE code = 'dp_dim' AND parent_id IS NULL) AS t),
              dimension = 'DP'
WHERE code = 'dp' AND parent_id IS NOT NULL
  AND EXISTS (SELECT 1 FROM (SELECT 1 FROM tag WHERE code = 'dp_dim' AND parent_id IS NULL) AS chk);
