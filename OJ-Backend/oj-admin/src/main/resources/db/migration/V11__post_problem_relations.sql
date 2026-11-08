-- 知识文章可关联多道练习题；题解继续使用 post.problem_id 保持单题归属。
CREATE TABLE IF NOT EXISTS `post_problem_relation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `post_id` BIGINT NOT NULL,
  `problem_id` BIGINT NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_post_problem_relation` (`post_id`, `problem_id`),
  KEY `idx_post_problem_relation_problem` (`problem_id`, `post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文章关联练习题';

-- MySQL 9.x does not accept ADD COLUMN IF NOT EXISTS. The dynamic statement
-- also lets a previously interrupted migration recover without data loss.
SET @is_official_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'post'
    AND column_name = 'is_official'
);
SET @is_official_sql := IF(
  @is_official_exists = 0,
  'ALTER TABLE `post` ADD COLUMN `is_official` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否官方题解''',
  'SELECT 1'
);
PREPARE add_is_official FROM @is_official_sql;
EXECUTE add_is_official;
DEALLOCATE PREPARE add_is_official;
