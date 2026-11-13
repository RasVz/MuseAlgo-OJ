-- 批量题面整理改为服务端异步任务：HTTP 只负责受理与查询，避免单请求跨过网关 300s 超时。
CREATE TABLE IF NOT EXISTS `problem_format_job` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  `creator_id` bigint NOT NULL COMMENT '发起人（教师/管理员）',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/DONE/CANCELED/FAILED',
  `ip_mode` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否同时做 IP 改名',
  `auto_apply` tinyint(1) NOT NULL DEFAULT '1' COMMENT '无阻断问题时是否直接落库',
  `total` int NOT NULL DEFAULT '0' COMMENT '题目总数',
  `applied` int NOT NULL DEFAULT '0' COMMENT '已直接应用数',
  `review` int NOT NULL DEFAULT '0' COMMENT '待人工审阅数',
  `failed` int NOT NULL DEFAULT '0' COMMENT '失败数',
  `skipped` int NOT NULL DEFAULT '0' COMMENT '取消后跳过数',
  `cancel_requested` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否已请求取消',
  `message` varchar(255) DEFAULT NULL COMMENT '任务级说明',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `finish_time` datetime DEFAULT NULL,
  `active_creator_id` bigint GENERATED ALWAYS AS
      (CASE WHEN status IN ('PENDING','RUNNING') THEN creator_id ELSE NULL END) STORED COMMENT '未完成任务归属，用于唯一约束',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pfj_active_creator` (`active_creator_id`),
  KEY `idx_pfj_creator` (`creator_id`,`id`),
  KEY `idx_pfj_status` (`status`,`update_time`),
  KEY `idx_pfj_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='批量题面整理任务';

-- 提案快照：审阅时不重调模型，保证「看到的」与「将要应用的」是同一份内容。
CREATE TABLE IF NOT EXISTS `problem_format_job_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `job_id` bigint NOT NULL,
  `problem_id` bigint NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/APPLIED/REVIEW/FAILED/SKIPPED',
  `title` varchar(128) DEFAULT NULL COMMENT '原题名（列表展示用）',
  `message` varchar(512) DEFAULT NULL COMMENT '结果说明',
  `error_code` varchar(64) DEFAULT NULL COMMENT '稳定错误码',
  `source_json` mediumtext COMMENT '原文快照 JSON',
  `formatted_json` mediumtext COMMENT '整理结果 JSON',
  `issues_json` text COMMENT '质量问题 JSON',
  `base_hash` char(64) DEFAULT NULL COMMENT '原文哈希（应用时校验）',
  `attempts` int NOT NULL DEFAULT '0' COMMENT '已尝试次数',
  `applied_fields` varchar(255) DEFAULT NULL COMMENT '已写入字段，逗号分隔',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pfji_job_problem` (`job_id`,`problem_id`),
  KEY `idx_pfji_job_status` (`job_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='批量题面整理任务单项';
