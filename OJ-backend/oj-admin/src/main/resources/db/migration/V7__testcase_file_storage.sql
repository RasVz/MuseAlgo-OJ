-- V7: 测试用例从数据库 LONGTEXT 迁移到文件系统存储
-- 将 input_case/output_case 替换为 input_file_path/output_file_path
-- 已有数据由应用层迁移脚本负责写出到文件并回填路径

ALTER TABLE `problem_case`
    ADD COLUMN `input_file_path`  VARCHAR(512) NULL COMMENT '测试输入文件路径（相对于 testcase.base-path）' AFTER `output_case`,
    ADD COLUMN `output_file_path` VARCHAR(512) NULL COMMENT '期望输出文件路径（相对于 testcase.base-path）' AFTER `input_file_path`;

-- 注意：input_case 和 output_case 列暂时保留，供数据迁移脚本使用。
-- 迁移完成并验证后可执行 DROP COLUMN。
-- ALTER TABLE `problem_case` DROP COLUMN `input_case`;
-- ALTER TABLE `problem_case` DROP COLUMN `output_case`;
