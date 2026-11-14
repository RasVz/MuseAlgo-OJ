-- 提交记录 AI 诊断：每条提交最多一份成功诊断，1:1 归属提交记录。
-- 不直接加宽 submission 主表，沿用 V52 对 submission_code 的拆表思路。
CREATE TABLE `submission_ai_diagnosis` (
  `submission_id` bigint NOT NULL COMMENT '提交ID（1:1）',
  `user_id` bigint NOT NULL,
  `status` varchar(16) NOT NULL COMMENT 'RUNNING/SUCCEEDED/FAILED',
  `execution_id` char(36) DEFAULT NULL COMMENT '本次执行的令牌，防止旧执行覆盖新结果',
  `content` text COMMENT '简短诊断正文',
  `error_code` varchar(64) DEFAULT NULL,
  `message` varchar(512) DEFAULT NULL,
  `model` varchar(255) DEFAULT NULL,
  `service_name` varchar(255) DEFAULT NULL,
  `prompt_tokens` int NOT NULL DEFAULT 0,
  `completion_tokens` int NOT NULL DEFAULT 0,
  `tokens_estimated` tinyint NOT NULL DEFAULT 0,
  `latency_ms` bigint NOT NULL DEFAULT 0,
  `start_time` datetime DEFAULT NULL,
  `finish_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`submission_id`),
  CONSTRAINT `fk_submission_ai_diagnosis_submission`
    FOREIGN KEY (`submission_id`) REFERENCES `submission` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提交记录一次性 AI 诊断';

-- 旧复盘记录系统已由一次性诊断取代；历史用量仍保留在 ai_usage。
DROP TABLE IF EXISTS `ai_submission_review`;
