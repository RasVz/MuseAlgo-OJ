-- 课件类型：0=Slidev 网页课件（默认），1=腾讯文档嵌入课件（v1 形态1：老师上传 PPT 到腾讯文档后贴嵌入链接）
ALTER TABLE `courseware`
    ADD COLUMN `type` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '课件类型: 0-Slidev网页, 1-腾讯文档嵌入' AFTER `url`;
