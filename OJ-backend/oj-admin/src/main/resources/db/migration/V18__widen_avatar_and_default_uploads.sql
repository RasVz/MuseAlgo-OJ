-- 头像列扩容：DiceBear 随机头像（SVG Data URI）远超 varchar(255)
ALTER TABLE `user`
  MODIFY COLUMN `avatar` text COMMENT '头像URL';
