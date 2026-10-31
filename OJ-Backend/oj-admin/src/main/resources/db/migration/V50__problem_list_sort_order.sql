-- 官方题单手动排序：sort_order 越小越靠前（0..n-1 由 update-sort 接口全量重排分配；默认 0=未参与排序，按创建时间倒序兜底）
ALTER TABLE `problem_list`
    ADD COLUMN `sort_order` int NOT NULL DEFAULT 0 COMMENT '手动排序值（越小越靠前，0=默认按创建时间）' AFTER `favorite_count`;
