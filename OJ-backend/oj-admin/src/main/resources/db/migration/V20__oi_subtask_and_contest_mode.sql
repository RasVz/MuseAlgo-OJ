-- ============================================================
-- OI 赛制与 Subtask 数据模型（ADR-0003）
-- 1) problem_case 增加子任务分组
-- 2) contest 增加赛制 mode（ACM / OI）
-- 可见性采用读取时计算（contest.mode + 时间窗），无需 submission 新列
-- ============================================================

-- 1) 子任务编号：1 起；历史用例全部归入第 1 组（单组 = ACM 行为）
ALTER TABLE problem_case
    ADD COLUMN subtask_no INT UNSIGNED NOT NULL DEFAULT 1
        COMMENT '子任务编号，1 起；同题同组为一组' AFTER case_order;

ALTER TABLE problem_case
    ADD KEY idx_problem_subtask (problem_id, subtask_no, case_order);

-- 2) 赛制：默认 ACM，历史比赛不受影响
ALTER TABLE contest
    ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'ACM'
        COMMENT 'ACM / OI' AFTER type;
