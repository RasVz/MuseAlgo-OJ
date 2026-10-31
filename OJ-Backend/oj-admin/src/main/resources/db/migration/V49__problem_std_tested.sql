-- 标准答案测试状态标记：强制保存=未测试(0)，测试并保存=已测试(1)
ALTER TABLE `problem`
    ADD COLUMN `std_tested` tinyint(1) NOT NULL DEFAULT 0 COMMENT '标准答案是否已通过测试数据验证（0-未测试，1-已测试）' AFTER `std_explanation`;
