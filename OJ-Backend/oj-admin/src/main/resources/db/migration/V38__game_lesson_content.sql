-- 教师可覆盖慕思星球固定教案的 Markdown 与各章节参考代码。
-- 可视化逐帧状态继续由版本化前端代码维护，避免内容编辑破坏动画协议。
ALTER TABLE `level`
  ADD COLUMN `lesson_tutorial` MEDIUMTEXT NULL COMMENT '教师覆盖的知识点 Markdown' AFTER `standard_answer_code`,
  ADD COLUMN `lesson_chapter_codes` MEDIUMTEXT NULL COMMENT '教师覆盖的章节标题到代码片段 JSON 映射' AFTER `lesson_tutorial`;
