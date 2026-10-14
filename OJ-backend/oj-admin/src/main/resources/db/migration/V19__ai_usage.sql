-- AI 调用用量流水：成本核算、配额审计与行为分析的数据源
CREATE TABLE IF NOT EXISTS `ai_usage` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL COMMENT '调用用户',
  `problem_id` BIGINT NULL COMMENT '关联题目（答疑场景）',
  `scene` VARCHAR(32) NOT NULL DEFAULT 'ASK' COMMENT '场景：ASK=题目答疑，预留 REVIEW/PLAN 等',
  `model` VARCHAR(64) NOT NULL COMMENT '模型名，如 deepseek-chat',
  `prompt_tokens` INT NOT NULL DEFAULT 0 COMMENT '输入 token（估算或 API 返回）',
  `completion_tokens` INT NOT NULL DEFAULT 0 COMMENT '输出 token',
  `total_tokens` INT NOT NULL DEFAULT 0,
  `tokens_estimated` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=按字符估算，0=API 返回的精确值',
  `latency_ms` BIGINT NOT NULL DEFAULT 0 COMMENT '端到端耗时（含流式生成）',
  `status` VARCHAR(24) NOT NULL COMMENT 'SUCCESS/FAIL/QUOTA_REJECTED/CONTEST_BLOCKED',
  `detail` VARCHAR(512) NULL COMMENT '失败原因或拦截说明（截断）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_usage_user_time` (`user_id`, `created_at`),
  KEY `idx_ai_usage_status_time` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 调用用量流水';
