-- 关卡绑定 Slidev 课件（PPT 教案）：管理员/教师可为每个游戏关卡绑定一个课件。
ALTER TABLE `level`
  ADD COLUMN `courseware_id` bigint DEFAULT NULL COMMENT '绑定课件ID（courseware 表，Slidev PPT）' AFTER `lesson_chapter_codes`;
