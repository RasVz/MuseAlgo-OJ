-- 成套试卷编程提交绑定作答会话：交卷只统计本次 attempt 的提交，练习提交不再混入。
-- client_request_id 用于网络重试幂等；历史数据保持 NULL，不参与唯一约束。
ALTER TABLE `submission`
  ADD COLUMN `exam_attempt_id` bigint NULL COMMENT '成套试卷作答会话ID' AFTER `vp_attempt_id`,
  ADD COLUMN `client_request_id` varchar(64) NULL COMMENT '客户端幂等键' AFTER `exam_attempt_id`,
  ADD UNIQUE KEY `uk_submission_user_request` (`user_id`, `client_request_id`),
  ADD KEY `idx_submission_exam_attempt_problem` (`exam_attempt_id`, `problem_id`);

-- 新会话使用 BOUND；历史会话保持 LEGACY，交卷时继续按原时间窗算法，避免重算历史成绩。
ALTER TABLE `exam_attempt`
  ADD COLUMN `scoring_version` varchar(16) NOT NULL DEFAULT 'LEGACY'
    COMMENT 'LEGACY=时间窗，BOUND=会话绑定' AFTER `status`;
