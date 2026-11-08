-- 依赖 V60：报告任务姓名快照；旧报告不追溯猜测历史姓名，保留 NULL 由界面显示 ID。
ALTER TABLE report_job ADD COLUMN student_name VARCHAR(255) DEFAULT NULL COMMENT '创建任务时学生姓名快照';
