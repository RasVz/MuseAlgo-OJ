-- ============================================================
-- V52: submission code 拆表（submission 归档方案阶段 1）
-- 说明:
--   1. code longtext 是 submission 主表最大体积来源（~70%+），
--      且判题回写 updateById 会反复重写 code（IO/binlog 浪费）。
--   2. 拆出 submission_code 1:1 表（懒加载），主表瘦身；
--      查询语义零变化（代码读取路径改为 JOIN / 单独查新表）。
--   3. 存量数据迁移后 DROP 主表 code 列。
-- ============================================================

CREATE TABLE `submission_code` (
    `submission_id` BIGINT       NOT NULL COMMENT '提交ID（1:1，主键即外键）',
    `code`          LONGTEXT     NOT NULL COMMENT '提交源码',
    PRIMARY KEY (`submission_id`),
    CONSTRAINT `fk_submission_code_submission`
        FOREIGN KEY (`submission_id`) REFERENCES `submission` (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='评测提交源码（submission 拆表，1:1 懒加载）';

-- 存量迁移：一次性搬入（当前 ~1 万行，毫秒~秒级；量级增大后可在低峰执行或分批）
INSERT INTO `submission_code` (`submission_id`, `code`)
SELECT `id`, `code` FROM `submission` WHERE `code` IS NOT NULL;

-- 主表移除 code 列（迁移后提交列表/判题回写不再触碰源码字段）
ALTER TABLE `submission` DROP COLUMN `code`;
