-- Table: submission_result
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `submission_result` (
  `submission_id` bigint NOT NULL,
  `status` tinyint unsigned NOT NULL,
  `score` int unsigned NOT NULL DEFAULT '0',
  `run_time` int unsigned NOT NULL DEFAULT '0',
  `run_memory` int unsigned NOT NULL DEFAULT '0',
  `error_message` text COLLATE utf8mb4_unicode_ci,
  `judged_at` datetime NOT NULL,
  PRIMARY KEY (`submission_id`),
  CONSTRAINT `fk_submission_result_submission` FOREIGN KEY (`submission_id`) REFERENCES `submission` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
;
