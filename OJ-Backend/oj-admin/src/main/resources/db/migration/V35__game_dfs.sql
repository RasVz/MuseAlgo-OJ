-- 图论群岛首批两个可玩关卡：DFS 遍历（用栈实现，不依赖递归）、连通块计数。
-- 面向小学五年级零基础学生：代码只使用 + - * / % 与栈，不涉及位运算。
INSERT IGNORE INTO problem (id, title, content, input_desc, output_desc, hint, time_limit, memory_limit, difficulty, author_id, source, pass_count, total_count, is_public) VALUES
(4006, '魔法森林探险（DFS 遍历）',
 '魔法森林里有 n 个地点和 m 条小路。小探险家从 1 号灯塔出发，用“深度优先搜索”把森林走一遍：每到一个地点，就优先走向编号最小的、还没去过的地方；走不动了就沿原路退回，再试别的路。请按访问顺序输出所有地点。',
 '第一行两个整数 n 和 m（2 ≤ n ≤ 50，1 ≤ m ≤ 1000）。接下来 m 行，每行两个整数 u 和 v，表示 u 和 v 之间有一条路。保证从 1 号地点出发能到达所有地点。',
 '输出一行 n 个整数，表示深度优先搜索的访问顺序，用空格分隔。',
 '用栈实现：访问 u 时，把 u 的所有没去过的邻居按编号从大到小压入栈（这样编号小的会先弹出）；弹出栈顶就是要访问的下一个地点。', 1000, 256, 900, 2, 'Game·图论群岛', 0, 0, 1),
(4007, '森林有几片？（连通块计数）',
 '探险队坐着热气球俯瞰魔法森林，发现森林被湖泊分成了好几片不相连的区域。请你数一数：n 个地点之间有 m 条小路，小路连起来的区域算同一片森林，整片森林一共有几片？',
 '第一行两个整数 n 和 m（1 ≤ n ≤ 50，0 ≤ m ≤ 1000）。接下来 m 行，每行两个整数 u 和 v，表示 u 和 v 之间有一条路。',
 '输出一个整数，表示森林被分成几片。',
 '从 1 号到 n 号依次检查：只要发现一个还没去过的地方，就说明找到一片新森林（计数加 1），然后用深度优先搜索把这一片全部走遍。', 1000, 256, 900, 2, 'Game·图论群岛', 0, 0, 1);

INSERT IGNORE INTO problem_tag (problem_id, tag_id)
SELECT game_tag.problem_id, tag.id
FROM (
    SELECT 4006 AS problem_id, 'graph' AS tag_code UNION ALL
    SELECT 4007, 'graph'
) AS game_tag
INNER JOIN tag ON tag.code = game_tag.tag_code;

INSERT IGNORE INTO problem_case (id, problem_id, case_order, input_file_path, output_file_path, score, case_type, status) VALUES
(40019, 4006, 1, '4006/1/input.txt', '4006/1/output.txt', 34, 0, 1),
(40020, 4006, 2, '4006/2/input.txt', '4006/2/output.txt', 33, 0, 1),
(40021, 4006, 3, '4006/3/input.txt', '4006/3/output.txt', 33, 0, 1),
(40022, 4007, 1, '4007/1/input.txt', '4007/1/output.txt', 34, 0, 1),
(40023, 4007, 2, '4007/2/input.txt', '4007/2/output.txt', 33, 0, 1),
(40024, 4007, 3, '4007/3/input.txt', '4007/3/output.txt', 33, 0, 1);

INSERT IGNORE INTO problem_sample (problem_id, sample_order, input, output) VALUES
(4006, 1, '6 7\n1 2\n1 3\n2 4\n3 4\n3 5\n4 6\n5 6', '1 2 4 6 5 3'),
(4007, 1, '6 3\n1 2\n3 4\n5 6', '3');

INSERT IGNORE INTO level (island, order_no, title, subtitle, problem_id, story, trace_input, op_threshold_3star, max_trace_events, xp_reward, status) VALUES
('graph_islands', 1, '魔法森林探险', '用深度优先搜索走遍整片森林', 4006,
 '魔法森林的地图摊开了：圆圈是地点，线段是小路。向导说：从 1 号灯塔出发，见到岔路就先往编号小的方向走，走到死胡同就回头再试别的路——这就是深度优先搜索。',
 '6 7\n1 2\n1 3\n2 4\n3 4\n3 5\n4 6\n5 6', 0, 2000, 120, 1),
('graph_islands', 2, '森林有几片', '数一数森林被分成了几片', 4007,
 '热气球升上天空，森林被湖泊切成一片一片。船长递来地图：从 1 号点开始一片一片数，每发现一片没走过的新区域就记一次数，把这一片走完再去下一片！',
 '6 3\n1 2\n3 4\n5 6', 0, 2000, 120, 1);
