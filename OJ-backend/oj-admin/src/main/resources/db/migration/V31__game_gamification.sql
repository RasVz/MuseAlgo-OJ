-- ============================================================
-- V31: 游戏化算法动画 OJ —— 游戏表结构 + MVP 三关种子数据
-- 注意：种子题目的判题用例文件需存在于 testcase.base-path（dev: data/testcases）
-- ============================================================

-- 1. 关卡表
CREATE TABLE IF NOT EXISTS `level` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `island` varchar(64) NOT NULL DEFAULT 'array_plain' COMMENT '所属岛屿',
  `order_no` int NOT NULL DEFAULT '0' COMMENT '岛内顺序',
  `title` varchar(128) NOT NULL COMMENT '关卡标题',
  `subtitle` varchar(255) DEFAULT NULL COMMENT '副标题/一句话剧情',
  `problem_id` bigint NOT NULL COMMENT '关联题目 ID',
  `story` text COMMENT '剧情文本（儿童向）',
  `template_code` mediumtext COMMENT '编辑器初始模板（含 @student_code_start/end）',
  `trace_input` text COMMENT '动画教学小数据（stdin，n<=20）',
  `op_threshold_3star` int NOT NULL DEFAULT '0' COMMENT '三星效率达标操作数阈值',
  `max_trace_events` int NOT NULL DEFAULT '2000' COMMENT 'trace 事件硬截断上限',
  `xp_reward` int NOT NULL DEFAULT '100' COMMENT '过关 XP 奖励',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '0-禁用 1-启用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_level_problem` (`problem_id`),
  KEY `idx_level_island_order` (`island`,`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='游戏关卡表';

-- 2. 用户关卡进度
CREATE TABLE IF NOT EXISTS `user_progress` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `level_id` bigint NOT NULL,
  `best_stars` tinyint NOT NULL DEFAULT '0' COMMENT '最佳星级 0-3',
  `xp_earned` int NOT NULL DEFAULT '0' COMMENT '累计获得 XP',
  `submission_count` int NOT NULL DEFAULT '0',
  `first_ac_at` datetime DEFAULT NULL,
  `last_submission_at` datetime DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_level` (`user_id`,`level_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户关卡进度';

-- 3. XP 流水（60 秒风控判定依据）
CREATE TABLE IF NOT EXISTS `xp_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `level_id` bigint NOT NULL,
  `submission_id` bigint NOT NULL,
  `xp_delta` int NOT NULL,
  `reason` varchar(64) NOT NULL COMMENT 'FIRST_AC/STAR_UPGRADE/FIRST_TRY',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_xp_user_time` (`user_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='XP 流水';

-- 4. 提交动画 trace（通道 B 产物）
CREATE TABLE IF NOT EXISTS `submission_trace` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/READY/FAILED/UNSUPPORTED',
  `events` longtext COMMENT '事件 JSON 数组',
  `op_count` int NOT NULL DEFAULT '0',
  `truncated` tinyint NOT NULL DEFAULT '0',
  `error_message` varchar(512) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trace_submission` (`submission_id`),
  KEY `idx_trace_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提交动画 trace';

-- ============================================================
-- MVP 种子：三个数组关卡（冒泡/选择/二分）
-- ============================================================
INSERT IGNORE INTO problem (id, title, content, input_desc, output_desc, hint, time_limit, memory_limit, difficulty, author_id, source, pass_count, total_count, is_public) VALUES
(4000, '能量核心修复（冒泡排序）',
 '机甲的能源核心由 n 块能量块组成，它们的能量值顺序乱了。请你用冒泡排序把能量值从小到大排好。',
 '第一行一个整数 n。第二行 n 个整数表示能量值。',
 '从小到大排序后的 n 个整数，空格分隔。',
 '冒泡排序：每一轮把最大的数“冒”到末尾。', 1000, 256, 800, 2, 'Game·数组平原', 0, 0, 1),
(4001, '星轨校准（选择排序）',
 '天文望远镜按星轨亮度校准顺序，请用选择排序把 n 颗星按亮度从小到大排列。',
 '第一行一个整数 n。第二行 n 个整数表示亮度。',
 '从小到大排序后的 n 个整数，空格分隔。',
 '选择排序：每轮从未排序部分选出最小值放到前面。', 1000, 256, 800, 2, 'Game·数组平原', 0, 0, 1),
(4002, '雷达锁定（二分查找）',
 '雷达扫描升序排列的信号序列，请用二分查找快速定位目标信号的下标（从 0 开始），找不到输出 -1。',
 '第一行 n target。第二行 n 个升序整数。',
 '目标下标或 -1。',
 '二分查找：每次把搜索范围缩小一半。', 1000, 256, 800, 2, 'Game·数组平原', 0, 0, 1);

-- 使用 tag.code 而非环境相关的自增 ID，保证干净库与已有库都能正确关联。
INSERT IGNORE INTO problem_tag (problem_id, tag_id)
SELECT game_tag.problem_id, tag.id
FROM (
    SELECT 4000 AS problem_id, 'array' AS tag_code UNION ALL
    SELECT 4001, 'array' UNION ALL
    SELECT 4002, 'search' UNION ALL
    SELECT 4002, 'array'
) AS game_tag
INNER JOIN tag ON tag.code = game_tag.tag_code;

INSERT IGNORE INTO problem_case (id, problem_id, case_order, input_file_path, output_file_path, score, case_type, status) VALUES
(40001, 4000, 1, '4000/1/input.txt',  '4000/1/output.txt',  34, 0, 1),
(40002, 4000, 2, '4000/2/input.txt',  '4000/2/output.txt',  33, 0, 1),
(40003, 4000, 3, '4000/3/input.txt',  '4000/3/output.txt',  33, 0, 1),
(40004, 4001, 1, '4001/1/input.txt',  '4001/1/output.txt',  34, 0, 1),
(40005, 4001, 2, '4001/2/input.txt',  '4001/2/output.txt',  33, 0, 1),
(40006, 4001, 3, '4001/3/input.txt',  '4001/3/output.txt',  33, 0, 1),
(40007, 4002, 1, '4002/1/input.txt',  '4002/1/output.txt',  34, 0, 1),
(40008, 4002, 2, '4002/2/input.txt',  '4002/2/output.txt',  33, 0, 1),
(40009, 4002, 3, '4002/3/input.txt',  '4002/3/output.txt',  33, 0, 1);

INSERT IGNORE INTO problem_sample (problem_id, sample_order, input, output) VALUES
(4000, 1, '5\n3 1 4 1 5', '1 1 3 4 5'),
(4001, 1, '5\n3 1 4 1 5', '1 1 3 4 5'),
(4002, 1, '6 9\n-1 0 3 5 9 12', '4');

INSERT IGNORE INTO level (island, order_no, title, subtitle, problem_id, story, trace_input, op_threshold_3star, max_trace_events, xp_reward, status) VALUES
('array_plain', 1, '能量核心修复', '用冒泡排序帮机甲恢复能量块顺序', 4000,
 '维修站的机械臂把能量块顺序弄乱了！小机甲“咔哒咔哒”走到你面前：帮我按能量值从小到大排好，每排好一轮，能量核心就会亮起一格。',
 '6\n5 2 8 1 9 3', 30, 2000, 100, 1),
('array_plain', 2, '星轨校准', '用选择排序把星星按亮度排好', 4001,
 '天文台的星轨图乱成一团。望远镜老爷爷说：每次找到最亮的那颗星放到前面，重复 n-1 次就能排好。',
 '6\n5 2 8 1 9 3', 25, 2000, 120, 1),
('array_plain', 3, '雷达锁定', '用二分查找快速锁定目标信号', 4002,
 '雷达屏幕上信号密密麻麻。指挥官命令：目标信号 9 藏在哪个位置？用二分查找，3 步之内就能锁定！',
 '7 9\n-1 0 3 5 9 12 15', 20, 2000, 120, 1);
