-- ============================================================
-- V48: 查重 M2 —— 比赛维度比对 + 赛后自动触发标记（2026-08-15）
-- 说明:
--   1. plagiarism_report 支持比赛维度：class_id 可空（比赛比对无单一班级），
--      新增 contest_id；唯一键从 (class_id, problem_id, sub_a, sub_b)
--      改为提交对维度 (submission_a_id, submission_b_id) —— 同一提交对
--      跨班级/比赛只保留一条（相似度最高者，先删后建）。
--   2. contest 表加 plagiarism_checked：赛后自动查重防重复标记。
-- ============================================================

ALTER TABLE `plagiarism_report`
    ADD COLUMN `contest_id` BIGINT NULL COMMENT '比赛ID（比赛维度比对，班级比对为 NULL）' AFTER `class_id`,
    MODIFY COLUMN `class_id` BIGINT NULL COMMENT '班级ID（比赛维度比对为 NULL）';

-- 清理重复提交对（保留 id 最小 = 最早入库的一条），再建唯一键
DELETE p1 FROM `plagiarism_report` p1
    JOIN `plagiarism_report` p2
      ON p1.submission_a_id = p2.submission_a_id
     AND p1.submission_b_id = p2.submission_b_id
     AND p1.id > p2.id;

ALTER TABLE `plagiarism_report`
    DROP INDEX `uk_pair`,
    ADD UNIQUE KEY `uk_submission_pair` (`submission_a_id`, `submission_b_id`);

ALTER TABLE `contest`
    ADD COLUMN `plagiarism_checked` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '赛后自动查重是否已执行（0-未执行 1-已执行）' AFTER `visible`;
