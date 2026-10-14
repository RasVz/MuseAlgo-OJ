-- 题目公开样例表：与判题用例（problem_case）分离，支持一道题多组样例
CREATE TABLE IF NOT EXISTS `problem_sample` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `problem_id` bigint NOT NULL COMMENT '题目ID',
  `sample_order` int unsigned NOT NULL DEFAULT '1' COMMENT '样例顺序，从 1 开始',
  `input` text COMMENT '样例输入',
  `output` text COMMENT '样例输出',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_problem_sample_problem_order` (`problem_id`,`sample_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='题目公开样例表';

-- 存量样例迁移：原 input_sample/output_sample 只存一组，迁为第一组样例
INSERT INTO problem_sample (problem_id, sample_order, input, output)
SELECT id, 1, input_sample, output_sample
FROM problem
WHERE input_sample IS NOT NULL
  AND output_sample IS NOT NULL
  AND (input_sample <> '' OR output_sample <> '');

-- 废弃旧字段（能删则删），样例统一走 problem_sample
ALTER TABLE `problem`
  DROP COLUMN `input_sample`,
  DROP COLUMN `output_sample`;

-- 内存限制默认值调整为 512MB（题面未声明时的平台默认）
ALTER TABLE `problem`
  MODIFY COLUMN `memory_limit` int DEFAULT '512' COMMENT '内存限制(MB)';
