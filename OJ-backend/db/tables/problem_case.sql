-- Table: problem_case
-- Database: oj_db
-- V8: 删除 input_case/output_case，测试用例内容存储在文件系统

CREATE TABLE IF NOT EXISTS `problem_case` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `problem_id` bigint NOT NULL COMMENT '题目ID',
  `case_order` int unsigned NOT NULL DEFAULT '1',
  `input_file_path` VARCHAR(512) NULL COMMENT '测试输入文件路径（相对于 testcase.base-path）',
  `output_file_path` VARCHAR(512) NULL COMMENT '期望输出文件路径（相对于 testcase.base-path）',
  `score` int DEFAULT '10' COMMENT '该测试点的分数',
  `subtask_no` int NULL COMMENT '子任务编号，从 1 开始；同题同组为一组（OI 部分分用，V20 已实际加列）',
  `case_type` tinyint unsigned NOT NULL DEFAULT '0',
  `status` tinyint DEFAULT '1' COMMENT '0-禁用, 1-启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_pid` (`problem_id`),
  KEY `idx_problem_case_problem_order` (`problem_id`,`case_order`),
  CONSTRAINT `chk_problem_case_type` CHECK ((`case_type` in (0,1)))
) ENGINE=InnoDB AUTO_INCREMENT=43 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='判题用例表（测试内容存储在本地文件系统）'
;
