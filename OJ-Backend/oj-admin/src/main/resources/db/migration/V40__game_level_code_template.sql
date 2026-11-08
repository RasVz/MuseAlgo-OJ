-- 关卡教学代码模板：展示在动画右侧「代码模板」面板，教师后台可改；
-- 为空时前端回退使用内置教案代码（gameLessons.js）。
ALTER TABLE `level`
    ADD COLUMN `code_template` text DEFAULT NULL COMMENT '教学展示代码模板（教师后台维护，空=用内置默认）' AFTER `lesson_chapter_codes`;
