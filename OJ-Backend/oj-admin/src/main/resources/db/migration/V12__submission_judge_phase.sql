-- 保留既有 status(0-5) 的最终判题语义；judge_phase 仅描述进行中的可见阶段。
-- See V11 for why this uses a conditional dynamic statement instead of
-- ADD COLUMN IF NOT EXISTS.
SET @judge_phase_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'submission'
    AND column_name = 'judge_phase'
);
SET @judge_phase_sql := IF(
  @judge_phase_exists = 0,
  'ALTER TABLE `submission` ADD COLUMN `judge_phase` VARCHAR(24) NOT NULL DEFAULT ''QUEUED'' COMMENT ''QUEUED/COMPILING/JUDGING/FINISHED/FAILED''',
  'SELECT 1'
);
PREPARE add_judge_phase FROM @judge_phase_sql;
EXECUTE add_judge_phase;
DEALLOCATE PREPARE add_judge_phase;
