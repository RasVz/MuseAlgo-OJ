-- V8: 删除已废弃的 input_case / output_case 列（数据已迁移到文件系统）

ALTER TABLE `problem_case`
    DROP COLUMN `input_case`,
    DROP COLUMN `output_case`;
