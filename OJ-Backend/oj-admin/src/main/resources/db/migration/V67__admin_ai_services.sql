-- Managed AI service catalog and singleton runtime selection.
-- Secrets are stored only as authenticated ciphertext; this migration never reads
-- or copies environment keys and starts in LEGACY mode.

CREATE TABLE IF NOT EXISTS `ai_service_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(50) NOT NULL COMMENT 'Administrator-defined display name',
  `protocol` VARCHAR(32) NOT NULL DEFAULT 'CHAT_COMPLETIONS' COMMENT 'Only Chat Completions compatible protocol in v1',
  `api_url` VARCHAR(512) NOT NULL COMMENT 'Complete request URL, no query/fragment/userinfo',
  `model` VARCHAR(128) NOT NULL COMMENT 'Exact provider model ID',
  `api_key_encrypted` TEXT NOT NULL COMMENT 'AES-GCM ciphertext: v1:nonce:ciphertext; never returned by APIs',
  `encryption_version` VARCHAR(16) NOT NULL DEFAULT 'v1' COMMENT 'Ciphertext envelope version',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'Available as a candidate, independent of runtime mode',
  `json_mode` TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'Send response_format=json_object for JSON calls',
  `stream_include_usage` TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'Request stream_options.include_usage',
  `max_tokens` INT NOT NULL DEFAULT 8192 COMMENT 'Output limit, 1..65536',
  `remark` VARCHAR(500) NULL COMMENT 'Optional purpose note',
  `version` INT NOT NULL DEFAULT 1 COMMENT 'Optimistic configuration version',
  `last_test_version` INT NULL COMMENT 'Configuration version bound to the latest completed test',
  `last_test_at` DATETIME(3) NULL COMMENT 'Latest completed test time',
  `last_test_status` VARCHAR(16) NULL COMMENT 'PASSED / FAILED',
  `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Soft delete flag',
  `created_by` BIGINT NULL,
  `updated_by` BIGINT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_ai_service_available` (`is_deleted`, `enabled`),
  KEY `idx_ai_service_test_status` (`last_test_status`),
  CONSTRAINT `chk_ai_service_protocol` CHECK (`protocol` IN ('CHAT_COMPLETIONS')),
  CONSTRAINT `chk_ai_service_test_status` CHECK (`last_test_status` IS NULL OR `last_test_status` IN ('PASSED', 'FAILED')),
  CONSTRAINT `chk_ai_service_max_tokens` CHECK (`max_tokens` BETWEEN 1 AND 65536)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Managed AI service configurations';

CREATE TABLE IF NOT EXISTS `ai_runtime_config` (
  `id` TINYINT NOT NULL,
  `mode` VARCHAR(16) NOT NULL DEFAULT 'LEGACY' COMMENT 'LEGACY / MANAGED / OFF',
  `current_service_id` BIGINT NULL COMMENT 'Required only in MANAGED mode',
  `version` INT NOT NULL DEFAULT 1 COMMENT 'Runtime optimistic version',
  `updated_by` BIGINT NULL,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  CONSTRAINT `chk_ai_runtime_singleton` CHECK (`id` = 1),
  CONSTRAINT `chk_ai_runtime_mode` CHECK (`mode` IN ('LEGACY', 'MANAGED', 'OFF')),
  CONSTRAINT `chk_ai_runtime_selection` CHECK (
    (`mode` = 'MANAGED' AND `current_service_id` IS NOT NULL)
    OR (`mode` IN ('LEGACY', 'OFF') AND `current_service_id` IS NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Singleton AI runtime selection';

INSERT INTO `ai_runtime_config` (`id`, `mode`, `current_service_id`, `version`, `updated_at`)
VALUES (1, 'LEGACY', NULL, 1, CURRENT_TIMESTAMP(3))
ON DUPLICATE KEY UPDATE `id` = `id`;

ALTER TABLE `ai_usage`
  ADD COLUMN `service_id` BIGINT NULL COMMENT 'Managed service ID snapshot' AFTER `model`,
  ADD COLUMN `service_name` VARCHAR(50) NULL COMMENT 'Service display-name snapshot' AFTER `service_id`,
  ADD COLUMN `runtime_mode` VARCHAR(16) NULL COMMENT 'Runtime mode snapshot' AFTER `service_name`,
  ADD COLUMN `config_version` INT NULL COMMENT 'Service configuration version snapshot' AFTER `runtime_mode`,
  ALGORITHM=INSTANT;

ALTER TABLE `ai_usage`
  ADD INDEX `idx_ai_usage_service_time` (`service_id`, `created_at`),
  ALGORITHM=INPLACE, LOCK=NONE;
