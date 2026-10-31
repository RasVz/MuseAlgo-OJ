-- 进制港湾首批两个可玩关卡：十进制转 K 进制（短除法）、K 进制转十进制（按权求和）。
-- 面向小学五年级零基础学生，代码只允许使用加减乘除与取余，不涉及位运算。
INSERT IGNORE INTO problem (id, title, content, input_desc, output_desc, hint, time_limit, memory_limit, difficulty, author_id, source, pass_count, total_count, is_public) VALUES
(4004, '灯塔信号换装（十进制转 K 进制）',
 '进制港湾的灯塔守要把一个十进制数字 n 用 k 进制的信号灯发出去。请把 n 转换成 k 进制并输出。注意：k 进制里 10 到 15 这几个“数字”要用大写字母 A 到 F 表示。',
 '一行两个整数 n 和 k（0 ≤ n ≤ 100000，2 ≤ k ≤ 16）。',
 '输出 n 的 k 进制表示；当 n 为 0 时输出 0。',
 '短除法：反复用 n % k 取余数、用 n / k 更新 n，直到 n 变成 0；先算出来的余数是低位，要倒着读。', 1000, 256, 800, 2, 'Game·进制港湾', 0, 0, 1),
(4005, '港口译电员（K 进制转十进制）',
 '译电员收到灯塔发来的一串 k 进制信号 s，请把它翻译回十进制整数，交给港口调度室。信号里的字母 A 到 F 分别表示数值 10 到 15。',
 '一行一个整数 k 和一个 k 进制字符串 s（2 ≤ k ≤ 16，s 只含数字 0-9 与大写字母 A-F，答案不超过 1000000）。',
 '输出 s 对应的十进制整数。',
 '按权求和：从左往右每读一位，answer = answer * k + 这一位的数值。', 1000, 256, 800, 2, 'Game·进制港湾', 0, 0, 1);

INSERT IGNORE INTO problem_tag (problem_id, tag_id)
SELECT game_tag.problem_id, tag.id
FROM (
    SELECT 4004 AS problem_id, 'math' AS tag_code UNION ALL
    SELECT 4005, 'math'
) AS game_tag
INNER JOIN tag ON tag.code = game_tag.tag_code;

INSERT IGNORE INTO problem_case (id, problem_id, case_order, input_file_path, output_file_path, score, case_type, status) VALUES
(40013, 4004, 1, '4004/1/input.txt', '4004/1/output.txt', 34, 0, 1),
(40014, 4004, 2, '4004/2/input.txt', '4004/2/output.txt', 33, 0, 1),
(40015, 4004, 3, '4004/3/input.txt', '4004/3/output.txt', 33, 0, 1),
(40016, 4005, 1, '4005/1/input.txt', '4005/1/output.txt', 34, 0, 1),
(40017, 4005, 2, '4005/2/input.txt', '4005/2/output.txt', 33, 0, 1),
(40018, 4005, 3, '4005/3/input.txt', '4005/3/output.txt', 33, 0, 1);

INSERT IGNORE INTO problem_sample (problem_id, sample_order, input, output) VALUES
(4004, 1, '13 2', '1101'),
(4005, 1, '2 1101', '13');

INSERT IGNORE INTO level (island, order_no, title, subtitle, problem_id, story, trace_input, op_threshold_3star, max_trace_events, xp_reward, status) VALUES
('base_harbor', 1, '灯塔信号换装', '用短除法把十进制数换成 K 进制信号', 4004,
 '灯塔守爷爷要发出今晚的航路信号，可信号灯只认识 k 进制的数字。他拍拍你的肩膀：用短除法，一次一次取余数，先算出来的余数要放到最后读，信号就换装完成啦！',
 '13 2', 0, 2000, 100, 1),
('base_harbor', 2, '港口译电员', '用按权求和把 K 进制信号翻回十进制', 4005,
 '清晨，灯塔发来一串神秘的 k 进制信号。译电员小姐姐递给你一张“权重表”：每一位都有自己的权，把每一位的数值乘以它的权再全部加起来，就能翻译回十进制！',
 '2 1101', 0, 2000, 120, 1);
