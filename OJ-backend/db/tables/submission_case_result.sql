-- Table: submission_case_result
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `submission_case_result` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `problem_case_id` bigint DEFAULT NULL,
  `case_order` int unsigned NOT NULL,
  `status` tinyint unsigned NOT NULL,
  `run_time` int unsigned NOT NULL DEFAULT '0',
  `run_memory` int unsigned NOT NULL DEFAULT '0',
  `error_message` text COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_submission_case_result_submission_order` (`submission_id`,`case_order`),
  KEY `fk_submission_case_result_case` (`problem_case_id`),
  CONSTRAINT `fk_submission_case_result_case` FOREIGN KEY (`problem_case_id`) REFERENCES `problem_case` (`id`),
  CONSTRAINT `fk_submission_case_result_submission` FOREIGN KEY (`submission_id`) REFERENCES `submission` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
;
