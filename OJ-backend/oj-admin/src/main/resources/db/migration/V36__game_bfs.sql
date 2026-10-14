-- 图论群岛第三个可玩关卡：BFS 遍历（独立课程，位于 DFS 之后、连通块之前）。
-- 4007（连通块）顺延为第 3 关。
INSERT IGNORE INTO problem (id, title, content, input_desc, output_desc, hint, time_limit, memory_limit, difficulty, author_id, source, pass_count, total_count, is_public) VALUES
(4008, '水波寻宝（BFS 遍历）',
 '小探险家学会了两种走法。这一次，他乘着热气球从 1 号地点出发，用“广度优先搜索”把森林走一遍：像水波一样一层一层往外扩——先把一步就能到的地点全部走完，再走两步能到的。请按访问顺序输出所有地点。',
 '第一行两个整数 n 和 m（2 ≤ n ≤ 50，1 ≤ m ≤ 1000）。接下来 m 行，每行两个整数 u 和 v，表示 u 和 v 之间有一条路。保证从 1 号地点出发能到达所有地点。',
 '输出一行 n 个整数，表示广度优先搜索的访问顺序，用空格分隔。',
 '用队列实现：访问 u 时，把 u 的所有没去过的邻居按编号从小到大排到队尾；队首出队就是要访问的下一个地点。', 1000, 256, 900, 2, 'Game·图论群岛', 0, 0, 1);

INSERT IGNORE INTO problem_tag (problem_id, tag_id)
SELECT 4008, id FROM tag WHERE code = 'graph';

INSERT IGNORE INTO problem_case (id, problem_id, case_order, input_file_path, output_file_path, score, case_type, status) VALUES
(40025, 4008, 1, '4008/1/input.txt', '4008/1/output.txt', 34, 0, 1),
(40026, 4008, 2, '4008/2/input.txt', '4008/2/output.txt', 33, 0, 1),
(40027, 4008, 3, '4008/3/input.txt', '4008/3/output.txt', 33, 0, 1);

INSERT IGNORE INTO problem_sample (problem_id, sample_order, input, output) VALUES
(4008, 1, '6 7\n1 2\n1 3\n2 4\n3 4\n3 5\n4 6\n5 6', '1 2 3 4 5 6');

INSERT IGNORE INTO level (island, order_no, title, subtitle, problem_id, story, trace_input, op_threshold_3star, max_trace_events, xp_reward, status) VALUES
('graph_islands', 2, '水波寻宝', '用广度优先搜索一层一层往外扩', 4008,
 '热气球升起来了！船长说：这次换一种走法，像水波一样一圈一圈往外扩——先把一步能到的地点全部走完，再走两步能到的。队列就是我们的寻宝清单：先来的先检查。',
 '6 7\n1 2\n1 3\n2 4\n3 4\n3 5\n4 6\n5 6', 0, 2000, 120, 1);

-- 连通块顺延为图论群岛第 3 关（BFS 课程插入 DFS 与连通块之间）
UPDATE level SET order_no = 3 WHERE problem_id = 4007 AND island = 'graph_islands';
