-- DP 星云首个可玩关卡：01 背包。题目输入为 n、背包容量，随后 n 行重量与价值。
INSERT IGNORE INTO problem (id, title, content, input_desc, output_desc, hint, time_limit, memory_limit, difficulty, author_id, source, pass_count, total_count, is_public) VALUES
(4003, '星云货舱规划（01 背包）',
 '星云运输船有一个容量为 capacity 的货舱。共有 n 件货物，第 i 件货物重量为 weight、价值为 value，每件货物至多选择一次。请输出在总重量不超过容量的条件下能获得的最大总价值。',
 '第一行两个整数 n 和 capacity。接下来 n 行，每行两个整数 weight 和 value。',
 '输出一个整数，表示最大总价值。',
 '定义 dp[i][c]：只考虑前 i 件物品、容量不超过 c 时的最大价值。选择第 i 件时应从 dp[i - 1] 转移。', 1000, 256, 1400, 2, 'Game·DP 星云', 0, 0, 1);

INSERT IGNORE INTO problem_tag (problem_id, tag_id)
SELECT 4003, id FROM tag WHERE code = 'dp';

INSERT IGNORE INTO problem_case (id, problem_id, case_order, input_file_path, output_file_path, score, case_type, status) VALUES
(40010, 4003, 1, '4003/1/input.txt', '4003/1/output.txt', 34, 0, 1),
(40011, 4003, 2, '4003/2/input.txt', '4003/2/output.txt', 33, 0, 1),
(40012, 4003, 3, '4003/3/input.txt', '4003/3/output.txt', 33, 0, 1);

INSERT IGNORE INTO problem_sample (problem_id, sample_order, input, output) VALUES
(4003, 1, '3 5\n2 3\n3 4\n4 5', '7');

INSERT IGNORE INTO level (island, order_no, title, subtitle, problem_id, story, trace_input, op_threshold_3star, max_trace_events, xp_reward, status) VALUES
('dp_nebula', 1, '星云货舱规划', '用 01 背包规划有限容量下的最大价值', 4003,
 '星云运输船的货舱只能容纳有限重量。导航员把货物的重量和价值投射成一张星表：每填好一个状态，航线就多亮一颗星。请在不超载的前提下，带回价值最高的组合。',
 '3 5\n2 3\n3 4\n4 5', 0, 2000, 160, 1);
