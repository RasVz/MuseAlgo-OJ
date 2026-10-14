-- Table: sys_permission
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `sys_permission` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `code` varchar(96) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_permission_code` (`code`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
;
