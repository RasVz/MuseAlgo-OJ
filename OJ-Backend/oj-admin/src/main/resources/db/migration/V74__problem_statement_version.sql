-- 题面版本：每次「应用 AI 结果 / 记录当前题面 / 启用历史版本」都会留下一条不可变快照，
-- 让教师可以回看某次优化改了什么，也能把任意历史版本重新设为当前题面。
CREATE TABLE IF NOT EXISTS `problem_statement_version` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `problem_id` bigint NOT NULL,
  `version_no` int NOT NULL COMMENT '题目内自增版本号',
  `status` varchar(16) NOT NULL COMMENT 'LIVE/ARCHIVED/CANDIDATE/DISCARDED',
  `source` varchar(24) NOT NULL COMMENT 'INITIAL/MANUAL/AI/AI_APPLIED',
  `title` varchar(128) NOT NULL,
  `content` mediumtext,
  `input_desc` text,
  `output_desc` text,
  `hint` text,
  `time_limit` int DEFAULT NULL,
  `memory_limit` int DEFAULT NULL,
  `samples_json` mediumtext COMMENT '样例快照 JSON 数组',
  `base_version_id` bigint DEFAULT NULL COMMENT '本次优化基于哪个版本',
  `job_id` bigint DEFAULT NULL COMMENT '产生该候选的整理任务',
  `issues_json` text COMMENT '质量检查结果',
  `note` varchar(255) DEFAULT NULL,
  `created_by` bigint DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  -- 同一道题只允许一个 LIVE 版本：把约束交给数据库，避免并发应用产生两个「当前版本」
  `live_problem_id` bigint GENERATED ALWAYS AS (CASE WHEN `status` = 'LIVE' THEN `problem_id` ELSE NULL END) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_psv_problem_version` (`problem_id`,`version_no`),
  UNIQUE KEY `uk_psv_live` (`live_problem_id`),
  KEY `idx_psv_problem_status` (`problem_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='题面版本';

-- 单题优化任务需要知道「基于哪个版本」以及「产出了哪个候选版本」
ALTER TABLE `problem_format_job_item`
  ADD COLUMN `base_version_id` bigint DEFAULT NULL COMMENT '优化基线版本' AFTER `base_hash`,
  ADD COLUMN `result_version_id` bigint DEFAULT NULL COMMENT '产出的候选版本' AFTER `base_version_id`;
