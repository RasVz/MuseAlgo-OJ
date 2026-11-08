-- 教师和管理员点击“载入标准答案”后可直接用此代码验证动画通道。
-- 学生端的关卡详情接口不会下发此字段。
ALTER TABLE `level`
  ADD COLUMN `standard_answer_code` mediumtext COMMENT '教师/管理员动画标准答案' AFTER `template_code`;
