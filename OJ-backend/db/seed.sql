-- ============================================================
-- OJ 演示种子数据：账号、班级、题目、判题用例、比赛、社区等示例内容。
-- 密码: 所有演示账号口令均为 "123456" (bcrypt)
-- 判题用例的 input.txt / output.txt 位于 OJ-backend/db/demo-testcases/，
-- 请用 ./scripts/db-seed.sh 导入（它会同时安装用例文件），不要只执行本 SQL。
-- ============================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 1. RBAC 权限与角色
-- ============================================================
INSERT INTO sys_permission (id, code, name) VALUES
(1, 'problem:manage',   '题目管理'),
(2, 'problem:view',     '题目查看'),
(3, 'contest:manage',   '比赛管理'),
(4, 'contest:view',     '比赛查看'),
(5, 'user:manage',      '用户管理'),
(6, 'class:manage',     '班级管理'),
(7, 'post:manage',      '帖子管理'),
(8, 'submission:view',  '提交查看');

INSERT INTO sys_role (id, code, name) VALUES
(1, 'admin',   '管理员'),
(2, 'teacher', '教师'),
(3, 'student', '学生');

-- 管理员: 全部权限
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(1,1),(1,2),(1,3),(1,4),(1,5),(1,6),(1,7),(1,8);
-- 教师: 题目/比赛/班级/帖子管理 + 查看权限
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(2,1),(2,2),(2,3),(2,4),(2,6),(2,7),(2,8);
-- 学生: 查看权限
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(3,2),(3,4),(3,8);

-- ============================================================
-- 2. 用户 (bcrypt 密码: 123456)
-- ============================================================
INSERT INTO `user` (id, username, password, nickname, role, email, real_name, school, major, rating, level, experience, submit_count, accepted_count)
VALUES
(1,  'admin',      '$2b$10$zn2OJAyl9xJC9EGtotgYWe42BUfsVEs1xqORR7bUThl6fP4S.xsy2', '系统管理员', 3, 'admin@oj.com',     '王管理', '算法睿思学院', NULL,       2000, 10, 5000, 0, 0),
(2,  'teacher01',  '$2b$10$zn2OJAyl9xJC9EGtotgYWe42BUfsVEs1xqORR7bUThl6fP4S.xsy2', '张老师',     2, 'zhang@oj.com',     '张明远', '算法睿思学院', '计算机科学', 1800, 8,  3200, 0, 0),
(3,  'teacher02',  '$2b$10$zn2OJAyl9xJC9EGtotgYWe42BUfsVEs1xqORR7bUThl6fP4S.xsy2', '李老师',     2, 'li@oj.com',        '李婉清', '算法睿思学院', '软件工程',   1750, 7,  2800, 0, 0),
(4,  'student01',  '$2b$10$zn2OJAyl9xJC9EGtotgYWe42BUfsVEs1xqORR7bUThl6fP4S.xsy2', '小明',       1, 'xm@oj.com',        '陈小明', '第一中学',     '高一(3)班', 1450, 4,  850,  5,  4),
(5,  'student02',  '$2b$10$zn2OJAyl9xJC9EGtotgYWe42BUfsVEs1xqORR7bUThl6fP4S.xsy2', '小红',       1, 'xh@oj.com',        '林小红', '第一中学',     '高一(3)班', 1320, 3,  620,  3,  2),
(6,  'student03',  '$2b$10$zn2OJAyl9xJC9EGtotgYWe42BUfsVEs1xqORR7bUThl6fP4S.xsy2', '小刚',       1, 'xg@oj.com',        '刘小刚', '第二中学',     '高二(1)班', 1510, 5,  1020, 5,  5),
(7,  'student04',  '$2b$10$zn2OJAyl9xJC9EGtotgYWe42BUfsVEs1xqORR7bUThl6fP4S.xsy2', '小丽',       1, 'xl@oj.com',        '赵小丽', '第一中学',     '高一(3)班', 1180, 2,  380,  3,  2),
(8,  'student05',  '$2b$10$zn2OJAyl9xJC9EGtotgYWe42BUfsVEs1xqORR7bUThl6fP4S.xsy2', '小强',       1, 'xq@oj.com',        '周小强', '第二中学',     '高二(1)班', 1380, 3,  540,  2,  2),
(9,  'student06',  '$2b$10$zn2OJAyl9xJC9EGtotgYWe42BUfsVEs1xqORR7bUThl6fP4S.xsy2', '小美',       1, 'xm2@oj.com',       '吴小美', '第三中学',     '初三(5)班', 1250, 2,  290,  0,  0),
(10, 'student07',  '$2b$10$zn2OJAyl9xJC9EGtotgYWe42BUfsVEs1xqORR7bUThl6fP4S.xsy2', '小杰',       1, 'xjj@oj.com',       '郑小杰', '第三中学',     '初三(5)班', 1400, 3,  470,  2,  2);

-- RBAC 关联 (sys_user_role 无自增 id，联合主键)
INSERT INTO sys_user_role (user_id, role_id) VALUES
(1, 1),
(2, 2), (3, 2),
(4, 3), (5, 3), (6, 3), (7, 3),
(8, 3), (9, 3), (10, 3);

-- 关注关系
INSERT INTO user_relation (id, follower_id, followed_id) VALUES
(1, 4, 6), (2, 6, 4), (3, 5, 4), (4, 8, 6),
(5, 4, 2), (6, 6, 2), (7, 10, 9);

-- ============================================================
-- 3. 班级
-- ============================================================
INSERT INTO class (id, name, teacher_id, student_count, invite_code, description, school) VALUES
(1, '算法基础班',   2, 4, 'ALGO2024',  '面向初学者的算法入门课程，涵盖基础数据结构和经典算法', '第一中学'),
(2, '数据结构进阶班', 2, 5, 'DS2024',    '在算法基础上深入学习高级数据结构和图论算法',           '第一中学');

-- 班级成员
INSERT INTO class_student (id, class_id, student_id, remark) VALUES
(1, 1, 4, '陈小明'), (2, 1, 5, '林小红'), (3, 1, 6, '刘小刚'), (4, 1, 7, '赵小丽'),
(5, 2, 4, '陈小明'), (6, 2, 5, '林小红'), (7, 2, 8, '周小强'), (8, 2, 9, '吴小美'), (9, 2, 10, '郑小杰');

-- ============================================================
-- 4. 知识图谱标签 (V5 种子 + 扩展)
-- ============================================================
-- 知识域 (parent_id = NULL)
INSERT INTO tag (id, code, name, parent_id, dimension, sort_order) VALUES
(1,  'algo',   '算法',     NULL, 'ALGORITHM',      1),
(2,  'ds',     '数据结构', NULL, 'DATA_STRUCTURE', 2),
(3,  'math',   '数学',     NULL, 'MATH',           3),
(4,  'graph',  '图论',     NULL, 'GRAPH',          4),
(5,  'string', '字符串',   NULL, 'STRING',         5);

-- 知识点 (parent_id → 知识域)
INSERT INTO tag (id, code, name, parent_id, dimension, sort_order) VALUES
(11, 'dp',             '动态规划', 1, 'ALGORITHM',      1),
(12, 'greedy',         '贪心',     1, 'ALGORITHM',      2),
(13, 'binary_search',  '二分查找', 1, 'ALGORITHM',      3),
(14, 'two_pointers',   '双指针',   1, 'ALGORITHM',      4),
(15, 'prefix_sum',     '前缀和',   1, 'ALGORITHM',      5),
(21, 'array',          '数组',     2, 'DATA_STRUCTURE', 1),
(22, 'stack_queue',    '栈与队列', 2, 'DATA_STRUCTURE', 2),
(23, 'tree',           '树',       2, 'DATA_STRUCTURE', 3),
(24, 'heap',           '堆',       2, 'DATA_STRUCTURE', 4),
(31, 'number_theory',  '数论',     3, 'MATH',           1),
(32, 'combinatorics',  '组合数学', 3, 'MATH',           2),
(41, 'shortest_path',  '最短路',   4, 'GRAPH',          1),
(42, 'mst',            '最小生成树',4, 'GRAPH',          2),
(43, 'topo_sort',      '拓扑排序', 4, 'GRAPH',          3),
(51, 'string_match',   '字符串匹配',5, 'STRING',        1);

-- ============================================================
-- 5. 题目 (15 道，覆盖各知识点)
-- ============================================================
-- 演示题目（15 道）。题面、样例与判题用例均为本项目原创编写，
-- 用于功能演示，不对应任何真实竞赛题目，也不取自第三方题库。
INSERT INTO problem (id, title, content, input_desc, output_desc, hint, time_limit, memory_limit, difficulty, author_id, source, pass_count, total_count) VALUES
(1,  '两数之和',
     '给定一个长度为 n 的整数序列，以及一个目标值 target。序列中恰好存在两个不同的位置，它们的元素之和等于 target，请找出这两个位置的下标。\n\n下标从 0 开始计数，同一个位置不能使用两次。',
     '第一行一个整数 n（2 ≤ n ≤ 1000）。\n第二行 n 个整数，绝对值不超过 10^9。\n第三行一个整数 target。',
     '两个下标，以空格分隔，较小的下标在前。',
     '用哈希表记录已出现元素的值与下标，可把查找从 O(n²) 降到 O(n)。', 1000, 256, 800, 2, '演示题', 35, 42),
(2,  '括号匹配',
     '给定一个只由小括号、中括号、大括号组成的字符串，判断它是否合法。\n\n合法的条件有两条：\n1. 每个左括号都必须由同类型的右括号闭合；\n2. 括号必须以正确的嵌套顺序闭合。',
     '一行字符串 s，长度不超过 10000。',
     '合法输出 YES，否则输出 NO。',
     '用栈：遇到左括号入栈，遇到右括号时检查栈顶是否为同类型的左括号。', 1000, 256, 800, 2, '演示题', 40, 48),
(3,  '最长公共前缀',
     '给定 n 个非空字符串，求它们的最长公共前缀。',
     '第一行一个整数 n（1 ≤ n ≤ 100）。\n接下来 n 行，每行一个非空字符串，长度不超过 200。',
     '最长公共前缀；不存在时输出一个空行。',
     '以第一个字符串为基准逐位比较，或纵向扫描所有字符串的同一列。', 1000, 256, 800, 2, '演示题', 38, 50),
(4,  '爬楼梯',
     '一段楼梯共有 n 级台阶，每次可以迈 1 级或 2 级。求走到顶部的不同走法数量。',
     '一行一个整数 n（1 ≤ n ≤ 45）。',
     '一个整数，表示走法数量。',
     '设 f(i) 为走到第 i 级的走法数，则 f(i) = f(i-1) + f(i-2)。', 1000, 256, 800, 2, '演示题', 45, 55),
(5,  '最大子段和',
     '给定一个长度为 n 的整数序列，找出一个连续且非空的子段，使其元素之和最大，输出这个最大和。',
     '第一行一个整数 n（1 ≤ n ≤ 100000）。\n第二行 n 个整数，绝对值不超过 10^4。',
     '一个整数，表示最大子段和。',
     'Kadane 算法：cur = max(a[i], cur + a[i])，并在过程中记录最大值。', 1000, 256, 1200, 2, '演示题', 30, 45),
(6,  '反转链表',
     '给定一个由 n 个节点组成的单链表，节点值按从头到尾的顺序给出，请输出反转后的节点值序列。',
     '第一行一个整数 n（1 ≤ n ≤ 1000）。\n第二行 n 个整数，表示从头到尾的节点值。',
     '反转后的节点值，以空格分隔。',
     '迭代法用 prev / cur / next 三个指针逐个改变指向。', 1000, 256, 800, 2, '演示题', 42, 52),
(7,  '二叉树的最大深度',
     '给定一棵以节点 1 为根的二叉树，求它的最大深度。深度定义为从根节点到最远叶子节点路径上的节点个数。\n\n每条边用「父节点 子节点 方向」描述，方向 0 表示左子节点，1 表示右子节点。',
     '第一行一个整数 n（1 ≤ n ≤ 10000），表示节点个数。\n接下来 n-1 行，每行三个整数：父节点、子节点、方向（0 左 / 1 右）。',
     '一个整数，表示最大深度。',
     '递归：depth(u) = 1 + max(depth(left), depth(right))。', 1000, 256, 800, 2, '演示题', 36, 45),
(8,  '二分查找',
     '给定一个长度为 n 的升序整型序列和一个目标值 target。若 target 存在于序列中则输出它的下标，否则输出 -1。\n\n序列中不存在重复元素。',
     '第一行两个整数 n 与 target（1 ≤ n ≤ 100000）。\n第二行 n 个升序排列的整数。',
     '目标值的下标（从 0 开始）；不存在则输出 -1。',
     '注意 mid = left + (right - left) / 2 的写法可以避免加法溢出。', 1000, 256, 800, 2, '演示题', 48, 56),
(9,  '零钱兑换',
     '给定 n 种硬币的面额和一个目标金额 amount，每种硬币可以使用任意多枚。求凑出 amount 所需的最少硬币枚数；若无法凑出则输出 -1。',
     '第一行两个整数 n 与 amount（1 ≤ n ≤ 12，0 ≤ amount ≤ 10000）。\n第二行 n 个互不相同的正整数，表示硬币面额。',
     '最少硬币枚数；无法凑出时输出 -1。',
     '完全背包：dp[j] = min(dp[j], dp[j - coin] + 1)。', 1000, 256, 1200, 2, '演示题', 25, 40),
(10, 'Dijkstra 最短路径',
     '给定一个 n 个节点、m 条边的有向带权图（节点编号 1..n），求从节点 1 到节点 n 的最短距离。边权均为正整数。',
     '第一行两个整数 n 与 m（1 ≤ n ≤ 10000，0 ≤ m ≤ 100000）。\n接下来 m 行，每行三个整数 u v w，表示一条从 u 到 v、权值为 w 的有向边（1 ≤ w ≤ 10000）。',
     '最短距离；不可达时输出 -1。节点 1 到自身的距离为 0。',
     '堆优化的 Dijkstra：每轮取出当前距离最小的未确定节点，松弛它的所有出边。', 2000, 512, 1600, 2, '演示题', 15, 30),
(11, '最小生成树',
     '给定一个 n 个节点、m 条边的无向带权连通图（节点编号 1..n），求其最小生成树的边权之和。',
     '第一行两个整数 n 与 m（2 ≤ n ≤ 10000，n-1 ≤ m ≤ 100000）。\n接下来 m 行，每行三个整数 u v w，表示一条连接 u 与 v、权值为 w 的无向边（1 ≤ w ≤ 10000）。',
     '一个整数，表示最小生成树的边权之和。',
     'Kruskal：把边按权值升序排序，用并查集跳过会成环的边。', 2000, 512, 1200, 2, '演示题', 20, 35),
(12, '快速幂',
     '给定整数 x 与非负整数 n，计算 x 的 n 次幂对 1000000007 取模的结果。',
     '一行两个整数 x 与 n（1 ≤ x ≤ 10^9，0 ≤ n ≤ 10^9）。',
     '一个整数，表示 x^n mod 1000000007。',
     '按二进制拆分指数：n 为偶数时 x^n = (x^(n/2))^2，为奇数时再乘一个 x。', 1000, 256, 800, 2, '演示题', 38, 44),
(13, '全排列',
     '给定 n 个互不相同的整数，按字典序输出它们的全部排列，每行一个。',
     '第一行一个整数 n（1 ≤ n ≤ 8）。\n第二行 n 个互不相同的整数。',
     '全部排列，每行一个，数字之间以空格分隔，整体按字典序升序排列。',
     '回溯逐位选择尚未使用的数字；先把输入排序即可天然得到字典序。', 1000, 256, 1200, 2, '演示题', 28, 40),
(14, 'KMP 字符串匹配',
     '给定文本串 text 和模式串 pattern，找出 pattern 在 text 中所有出现位置的起始下标（从 0 开始），允许出现位置重叠。',
     '第一行一个字符串 text（长度不超过 100000）。\n第二行一个字符串 pattern（长度不超过 text 的长度）。\n两个串均只含小写字母。',
     '所有匹配的起始下标，按升序以空格分隔；没有匹配时输出 -1。',
     'KMP 先由模式串自身求出 next（最长公共前后缀）数组，再线性扫描文本串。', 1000, 256, 1200, 2, '演示题', 18, 32),
(15, '拓扑排序',
     '给定 n 个任务与 m 条依赖关系（a b 表示任务 a 必须先于任务 b 完成），输出一个合法的完成顺序；若依赖关系存在环则输出 -1。\n\n当存在多个合法顺序时，输出字典序最小的那一个。',
     '第一行两个整数 n 与 m（1 ≤ n ≤ 100000，0 ≤ m ≤ 200000）。\n接下来 m 行，每行两个整数 a b，表示 a 必须先于 b。',
     '一行 n 个整数，表示字典序最小的合法拓扑序；存在环时输出 -1。',
     'Kahn 算法：用最小堆维护当前入度为 0 的节点，即可保证得到的顺序字典序最小。', 1000, 256, 1200, 2, '演示题', 22, 36);

-- 公开样例（与判题用例分离，一道题可有多组）
INSERT INTO problem_sample (problem_id, sample_order, input, output) VALUES
(1, 1, '4\n2 7 11 15\n9', '0 1'),
(2, 1, '()[]{}', 'YES'),
(3, 1, '3\nflower\nflow\nflight', 'fl'),
(4, 1, '5', '8'),
(5, 1, '9\n-2 1 -3 4 -1 2 1 -5 4', '6'),
(6, 1, '5\n1 2 3 4 5', '5 4 3 2 1'),
(7, 1, '3\n1 2 0\n1 3 1', '2'),
(8, 1, '6 9\n-1 0 3 5 9 12', '4'),
(9, 1, '3 11\n1 2 5', '3'),
(10, 1, '3 3\n1 2 2\n2 3 1\n1 3 5', '3'),
(11, 1, '4 5\n1 2 2\n1 3 3\n2 3 1\n2 4 4\n3 4 5', '7'),
(12, 1, '2 10', '1024'),
(13, 1, '3\n1 2 3', '1 2 3\n1 3 2\n2 1 3\n2 3 1\n3 1 2\n3 2 1'),
(14, 1, 'ababcabcabab\nab', '0 2 5 7 10'),
(15, 1, '4 4\n1 2\n2 3\n1 3\n3 4', '1 2 3 4');

-- 题目-标签关联
INSERT INTO problem_tag (problem_id, tag_id) VALUES
(1, 21),(1, 11),                              -- 两数之和: 数组+动态规划(hash)
(2, 22),                                       -- 有效括号: 栈与队列
(3, 51),                                       -- 最长公共前缀: 字符串匹配
(4, 11),                                       -- 爬楼梯: 动态规划
(5, 11),(5, 21),                              -- 最大子数组和: DP+数组
(6, 23),                                       -- 反转链表: 树(链表)
(7, 23),                                       -- 二叉树深度: 树
(8, 13),(8, 21),                              -- 二分查找: 二分+数组
(9, 11),                                       -- 零钱兑换: DP
(10, 41),(10, 24),                            -- Dijkstra: 最短路+堆
(11, 42),                                      -- 最小生成树: MST
(12, 31),                                      -- 快速幂: 数论
(13, 32),                                      -- 全排列: 组合数学
(14, 51),                                      -- KMP: 字符串匹配
(15, 43);                                      -- 拓扑排序: 拓扑排序

-- 判题用例。用例内容与期望输出由 scripts/gen-demo-testcases.py 生成，
-- 对应的 input.txt / output.txt 位于 OJ-backend/db/demo-testcases/。
-- 安装：./scripts/db-seed.sh 会把该目录复制到 oj.testcase.base-path。
-- case_type: 0 = 计分用例，1 = 公开样例（不计分）。
INSERT INTO problem_case (id, problem_id, case_order, input_file_path, output_file_path, score, case_type, status) VALUES
(1, 1, 1, '1/1/input.txt', '1/1/output.txt', 20, 0, 1),
(2, 1, 2, '1/2/input.txt', '1/2/output.txt', 20, 0, 1),
(3, 1, 3, '1/3/input.txt', '1/3/output.txt', 20, 0, 1),
(4, 1, 4, '1/4/input.txt', '1/4/output.txt', 20, 0, 1),
(5, 1, 5, '1/5/input.txt', '1/5/output.txt', 20, 0, 1),
(6, 2, 1, '2/6/input.txt', '2/6/output.txt', 25, 0, 1),
(7, 2, 2, '2/7/input.txt', '2/7/output.txt', 25, 0, 1),
(8, 2, 3, '2/8/input.txt', '2/8/output.txt', 25, 0, 1),
(9, 2, 4, '2/9/input.txt', '2/9/output.txt', 25, 0, 1),
(10, 3, 1, '3/10/input.txt', '3/10/output.txt', 25, 0, 1),
(11, 3, 2, '3/11/input.txt', '3/11/output.txt', 25, 0, 1),
(12, 3, 3, '3/12/input.txt', '3/12/output.txt', 25, 0, 1),
(13, 3, 4, '3/13/input.txt', '3/13/output.txt', 25, 0, 1),
(14, 4, 1, '4/14/input.txt', '4/14/output.txt', 25, 0, 1),
(15, 4, 2, '4/15/input.txt', '4/15/output.txt', 25, 0, 1),
(16, 4, 3, '4/16/input.txt', '4/16/output.txt', 25, 0, 1),
(17, 4, 4, '4/17/input.txt', '4/17/output.txt', 25, 0, 1),
(18, 5, 1, '5/18/input.txt', '5/18/output.txt', 25, 0, 1),
(19, 5, 2, '5/19/input.txt', '5/19/output.txt', 25, 0, 1),
(20, 5, 3, '5/20/input.txt', '5/20/output.txt', 25, 0, 1),
(21, 5, 4, '5/21/input.txt', '5/21/output.txt', 25, 0, 1),
(22, 6, 1, '6/22/input.txt', '6/22/output.txt', 25, 0, 1),
(23, 6, 2, '6/23/input.txt', '6/23/output.txt', 25, 0, 1),
(24, 6, 3, '6/24/input.txt', '6/24/output.txt', 25, 0, 1),
(25, 6, 4, '6/25/input.txt', '6/25/output.txt', 25, 0, 1),
(26, 7, 1, '7/26/input.txt', '7/26/output.txt', 25, 0, 1),
(27, 7, 2, '7/27/input.txt', '7/27/output.txt', 25, 0, 1),
(28, 7, 3, '7/28/input.txt', '7/28/output.txt', 25, 0, 1),
(29, 7, 4, '7/29/input.txt', '7/29/output.txt', 25, 0, 1),
(30, 8, 1, '8/30/input.txt', '8/30/output.txt', 25, 0, 1),
(31, 8, 2, '8/31/input.txt', '8/31/output.txt', 25, 0, 1),
(32, 8, 3, '8/32/input.txt', '8/32/output.txt', 25, 0, 1),
(33, 8, 4, '8/33/input.txt', '8/33/output.txt', 25, 0, 1),
(34, 1, 0, '1/34/input.txt', '1/34/output.txt', 0, 1, 1),
(35, 2, 0, '2/35/input.txt', '2/35/output.txt', 0, 1, 1),
(36, 3, 0, '3/36/input.txt', '3/36/output.txt', 0, 1, 1),
(37, 9, 1, '9/37/input.txt', '9/37/output.txt', 25, 0, 1),
(38, 9, 2, '9/38/input.txt', '9/38/output.txt', 25, 0, 1),
(39, 9, 3, '9/39/input.txt', '9/39/output.txt', 25, 0, 1),
(40, 9, 4, '9/40/input.txt', '9/40/output.txt', 25, 0, 1),
(41, 10, 1, '10/41/input.txt', '10/41/output.txt', 25, 0, 1),
(42, 10, 2, '10/42/input.txt', '10/42/output.txt', 25, 0, 1),
(43, 10, 3, '10/43/input.txt', '10/43/output.txt', 25, 0, 1),
(44, 10, 4, '10/44/input.txt', '10/44/output.txt', 25, 0, 1),
(45, 11, 1, '11/45/input.txt', '11/45/output.txt', 25, 0, 1),
(46, 11, 2, '11/46/input.txt', '11/46/output.txt', 25, 0, 1),
(47, 11, 3, '11/47/input.txt', '11/47/output.txt', 25, 0, 1),
(48, 11, 4, '11/48/input.txt', '11/48/output.txt', 25, 0, 1),
(49, 12, 1, '12/49/input.txt', '12/49/output.txt', 25, 0, 1),
(50, 12, 2, '12/50/input.txt', '12/50/output.txt', 25, 0, 1),
(51, 12, 3, '12/51/input.txt', '12/51/output.txt', 25, 0, 1),
(52, 12, 4, '12/52/input.txt', '12/52/output.txt', 25, 0, 1),
(53, 13, 1, '13/53/input.txt', '13/53/output.txt', 25, 0, 1),
(54, 13, 2, '13/54/input.txt', '13/54/output.txt', 25, 0, 1),
(55, 13, 3, '13/55/input.txt', '13/55/output.txt', 25, 0, 1),
(56, 13, 4, '13/56/input.txt', '13/56/output.txt', 25, 0, 1),
(57, 14, 1, '14/57/input.txt', '14/57/output.txt', 25, 0, 1),
(58, 14, 2, '14/58/input.txt', '14/58/output.txt', 25, 0, 1),
(59, 14, 3, '14/59/input.txt', '14/59/output.txt', 25, 0, 1),
(60, 14, 4, '14/60/input.txt', '14/60/output.txt', 25, 0, 1),
(61, 15, 1, '15/61/input.txt', '15/61/output.txt', 25, 0, 1),
(62, 15, 2, '15/62/input.txt', '15/62/output.txt', 25, 0, 1),
(63, 15, 3, '15/63/input.txt', '15/63/output.txt', 25, 0, 1),
(64, 15, 4, '15/64/input.txt', '15/64/output.txt', 25, 0, 1);


-- ============================================================
-- 6. 题单
-- ============================================================
INSERT INTO problem_list (id, title, creator_id, description, type, class_id, privacy, problem_count) VALUES
(1, '第一周作业：数组与字符串',  2, '本周学习了数组和字符串基础，请完成以下练习巩固。', 1, 1, 0, 4),
(2, '动态规划专项练习',         2, '精选 DP 经典题目，从入门到进阶。',             0, NULL, 1, 4),
(3, '图论算法合集',             2, '最短路、最小生成树、拓扑排序等图论算法。',       0, NULL, 1, 3);

INSERT INTO problem_list_item (id, list_id, problem_id, sort) VALUES
(1, 1, 1, 1), (2, 1, 2, 2), (3, 1, 3, 3), (4, 1, 6, 4),
(5, 2, 4, 1), (6, 2, 5, 2), (7, 2, 9, 3), (8, 2, 13, 4),
(9, 3, 10, 1), (10, 3, 11, 2), (11, 3, 15, 3);

-- ============================================================
-- 7. 比赛
-- ============================================================
INSERT INTO contest (id, title, description, start_time, end_time, duration, type, author_id, participant_count, class_id, visible) VALUES
(1, '2024 算法基础班周赛 #1',
    '算法基础班第一次周赛，共 4 题，难度从简单到中等。赛后会公布题解和排名。',
    '2024-12-01 14:00:00', '2024-12-01 16:00:00', 120, 'Class', 2, 4, 1, 1),
(2, '2024 信奥校选模拟赛',
    '模拟 NOIP 普及组难度，面向全校学生。前 5 名可获得校队集训资格。',
    '2024-12-15 08:30:00', '2024-12-15 12:00:00', 210, 'Public', 2, 7, NULL, 1);

-- 比赛-题目关联
INSERT INTO problem_contest (id, contest_id, problem_id, problem_order, score) VALUES
(1, 1, 1, 1, 100), (2, 1, 2, 2, 100), (3, 1, 4, 3, 100), (4, 1, 6, 4, 100),
(5, 2, 3, 1, 100), (6, 2, 5, 2, 100), (7, 2, 8, 3, 100), (8, 2, 9, 4, 150),
(9, 2, 10, 5, 200), (10, 2, 11, 6, 200);

-- 比赛报名
INSERT INTO contest_user (id, contest_id, user_id) VALUES
(1, 1, 4), (2, 1, 5), (3, 1, 6), (4, 1, 7),
(5, 2, 4), (6, 2, 5), (7, 2, 6), (8, 2, 7),
(9, 2, 8), (10, 2, 9), (11, 2, 10);

-- 比赛排名 (比赛1已结束)
INSERT INTO contest_rank (id, contest_id, user_id, rank_num, solved_count, penalty_time, total_score, rating_before, rating_after) VALUES
(1, 1, 6, 1, 4, 45, 400, 1480, 1510),
(2, 1, 4, 2, 3, 52, 300, 1420, 1450),
(3, 1, 5, 3, 2, 38, 200, 1300, 1320),
(4, 1, 7, 4, 1, 15, 100, 1170, 1180);

-- Rating 变化记录
INSERT INTO user_rating (id, user_id, contest_id, rating, rating_change, `rank`) VALUES
(1, 6, 1, 1510, 30,  1),
(2, 4, 1, 1450, 30,  2),
(3, 5, 1, 1320, 20,  3),
(4, 7, 1, 1180, 10,  4);

-- ============================================================
-- 8. 提交记录 (模拟学生做题)
-- ============================================================
-- 提交主表（注意：V52 起源码已拆分到 submission_code，主表不再有 code 列）
INSERT INTO submission (id, problem_id, user_id, language, score, status, run_time, run_memory, create_time) VALUES
-- 学生4 的提交
(1,  1, 4, 'cpp',    100, 1, 15,   20480, '2024-12-20 10:00:00'),
(2,  2, 4, 'cpp',    100, 1, 8,    18432, '2024-12-20 10:30:00'),
(3,  4, 4, 'python', 100, 1, 25,   22528, '2024-12-20 11:00:00'),
(4,  5, 4, 'cpp',    0,   2, 30,   25600, '2024-12-20 14:00:00'),
(5,  5, 4, 'cpp',    100, 1, 18,   21504, '2024-12-20 14:15:00'),
-- 学生6 的提交
(6,  1, 6, 'java',   100, 1, 22,   30720, '2024-12-20 10:05:00'),
(7,  2, 6, 'java',   100, 1, 12,   27648, '2024-12-20 10:35:00'),
(8,  4, 6, 'java',   100, 1, 28,   31744, '2024-12-20 11:10:00'),
(9,  6, 6, 'java',   100, 1, 10,   26624, '2024-12-20 15:00:00'),
(10, 8, 6, 'java',   100, 1, 8,    25600, '2024-12-20 15:30:00'),
-- 学生5 的提交
(11, 1, 5, 'python', 0,   5, 2000, 51200, '2024-12-20 10:10:00'),
(12, 1, 5, 'python', 100, 1, 45,   28672, '2024-12-20 10:25:00'),
(13, 2, 5, 'python', 100, 1, 18,   24576, '2024-12-20 10:40:00'),
-- 学生7 的提交
(14, 1, 7, 'cpp',    100, 1, 20,   20992, '2024-12-20 10:20:00'),
(15, 2, 7, 'cpp',    0,   3, 0,    0,     '2024-12-20 10:50:00'),
(16, 2, 7, 'cpp',    100, 1, 10,   19456, '2024-12-20 10:55:00'),
-- 学生8 的提交
(17, 4, 8, 'cpp',    100, 1, 16,   22016, '2024-12-21 09:00:00'),
(18, 6, 8, 'cpp',    100, 1, 12,   20480, '2024-12-21 09:30:00'),
-- 学生10 的提交
(19, 10, 10, 'cpp',  100, 1, 35,   35840, '2024-12-21 10:00:00'),
(20, 11, 10, 'cpp',  100, 1, 28,   32768, '2024-12-21 10:30:00');

-- 提交源码（V52 拆表后独立存放，与 submission.id 1:1 对应）
INSERT INTO submission_code (submission_id, code) VALUES
(1,  '// AC solution for Two Sum\n#include <bits/stdc++.h>\nusing namespace std;\nint main() {\n    // hash map solution\n    return 0;\n}'),
(2,  '// AC solution for Valid Parentheses'),
(3,  '// AC solution for Climbing Stairs (DP)'),
(4,  '// WA: Kadane implementation bug'),
(5,  '// AC: fixed Kadane'),
(6,  '// AC Two Sum'),
(7,  '// AC Valid Parentheses (stack)'),
(8,  '// AC DP solution'),
(9,  '// AC Reverse Linked List'),
(10, '// AC Binary Search'),
(11, '// WA: brute force TLE'),
(12, '// AC: hash map'),
(13, '// AC: stack'),
(14, '// AC'),
(15, '// CE: missing semicolon'),
(16, '// AC'),
(17, '// AC DP'),
(18, '// AC'),
(19, '// Dijkstra + priority_queue'),
(20, '// Kruskal + DSU');

-- 提交结果
INSERT INTO submission_result (submission_id, status, score, run_time, run_memory, judged_at) VALUES
(1, 1, 100, 15, 20480, '2024-12-20 10:00:02'),
(2, 1, 100, 8,  18432, '2024-12-20 10:30:02'),
(3, 1, 100, 25, 22528, '2024-12-20 11:00:02'),
(4, 2, 0,   30, 25600, '2024-12-20 14:00:03'),
(5, 1, 100, 18, 21504, '2024-12-20 14:15:02'),
(6, 1, 100, 22, 30720, '2024-12-20 10:05:02'),
(7, 1, 100, 12, 27648, '2024-12-20 10:35:02'),
(8, 1, 100, 28, 31744, '2024-12-20 11:10:02'),
(9, 1, 100, 10, 26624, '2024-12-20 15:00:02'),
(10, 1, 100, 8,  25600, '2024-12-20 15:30:02'),
(11, 5, 0,   2000, 51200, '2024-12-20 10:10:03'),
(12, 1, 100, 45, 28672, '2024-12-20 10:25:02'),
(13, 1, 100, 18, 24576, '2024-12-20 10:40:02'),
(14, 1, 100, 20, 20992, '2024-12-20 10:20:02'),
(15, 3, 0,   0,   0,     '2024-12-20 10:50:01'),
(16, 1, 100, 10, 19456, '2024-12-20 10:55:02'),
(17, 1, 100, 16, 22016, '2024-12-21 09:00:02'),
(18, 1, 100, 12, 20480, '2024-12-21 09:30:02'),
(19, 1, 100, 35, 35840, '2024-12-21 10:00:03'),
(20, 1, 100, 28, 32768, '2024-12-21 10:30:03');

-- ============================================================
-- 9. 帖子
-- ============================================================
INSERT INTO post (id, title, content, summary, author_id, type, category, views, likes, comment_count, is_essence) VALUES
(1, '欢迎加入算法基础班！',
    '# 欢迎！\n\n欢迎大家加入算法基础班！我是张老师。\n\n本课程将带领大家从零开始学习算法和数据结构。\n\n## 课程安排\n- 第一周：数组与哈希表\n- 第二周：栈与队列\n- 第三周：链表与递归\n- 第四周：树与二叉树\n\n大家加油！',
    '算法基础班开课公告', 2, 0, 'announcement', 156, 3, 4, 0),
(2, '二分查找题解 —— 彻底搞懂边界条件',
    '# 二分查找详解\n\n二分查找看似简单，但边界条件经常让人困惑。\n\n## 核心模板\n```cpp\nint l = 0, r = n - 1;\nwhile (l <= r) {\n    int mid = l + (r - l) / 2;\n    if (nums[mid] == target) return mid;\n    else if (nums[mid] < target) l = mid + 1;\n    else r = mid - 1;\n}\nreturn -1;\n```\n\n## 关键点\n1. `mid = l + (r - l) / 2` 防止溢出\n2. `l <= r` 而非 `l < r`\n\n希望这篇题解对大家有帮助！',
    '深入讲解二分查找的边界处理', 6, 1, 'algorithm', 320, 7, 4, 1),
(3, '动态规划入门：从爬楼梯到背包问题',
    '# DP 入门\n\nDP = 状态 + 转移 + 初始化\n\n## 爬楼梯（入门）\n`dp[i] = dp[i-1] + dp[i-2]`\n\n## 零钱兑换（进阶）\n`dp[i] = min(dp[i], dp[i-coin] + 1)`\n\n理解 DP 的关键是画出状态转移表。',
    'DP 从入门到进阶的学习路径', 4, 2, 'algorithm', 245, 4, 2, 1);

-- ============================================================
-- 10. 评论
-- ============================================================
INSERT INTO comment (id, user_id, entity_id, entity_type, content, parent_id, reply_user_id, like_count, reply_count) VALUES
(1, 4, 1, 1, '谢谢张老师！我会认真学习的。', 0, 0, 1, 1),
(2, 2, 1, 1, '加油小明！', 1, 4, 0, 0),
(3, 6, 1, 1, '老师，有没有推荐的参考书？', 0, 0, 0, 1),
(4, 2, 1, 1, '可以参考《算法竞赛入门经典》', 3, 6, 0, 0),
(5, 5, 2, 1, '这篇讲解太清晰了！终于搞懂mid为什么这样写', 0, 0, 3, 2),
(6, 8, 2, 1, '同感！之前在边界一直出错', 5, 5, 0, 0),
(7, 9, 2, 1, '请问while条件什么时候用 < 什么时候用 <= 呢？', 5, 5, 0, 1),
(8, 6, 2, 1, '搜索区间是闭区间就用 <=，左闭右开用 <', 7, 9, 2, 0),
(9, 4, 3, 1, 'DP真的需要多练，感谢分享！', 0, 0, 0, 0),
(10, 10, 3, 1, '零钱兑换那题我卡了很久，这个状态转移讲得很清楚', 0, 0, 0, 0);

-- ============================================================
-- 11. 点赞
-- ============================================================
INSERT INTO user_like (id, user_id, post_id, action_type) VALUES
(1, 4, 2, 1), (2, 5, 2, 1), (3, 6, 2, 1), (4, 7, 2, 1),
(5, 8, 2, 1), (6, 9, 2, 1), (7, 10, 2, 1),
(8, 4, 3, 1), (9, 5, 3, 1), (10, 6, 3, 1), (11, 8, 3, 1),
(12, 4, 1, 1), (13, 6, 1, 1), (14, 5, 1, 1);

INSERT INTO comment_like (id, user_id, comment_id) VALUES
(1, 4, 5), (2, 6, 5), (3, 8, 5), (4, 9, 8), (5, 5, 8), (6, 4, 1);

-- ============================================================
-- 12. 收藏
-- ============================================================
INSERT INTO user_favorite (id, user_id, target_id, target_type, status) VALUES
(1, 4, 1, 1, 1), (2, 4, 4, 1, 1), (3, 4, 5, 1, 1),
(4, 6, 10, 1, 1), (5, 6, 11, 1, 1), (6, 6, 2, 4, 1),
(7, 5, 2, 4, 1), (8, 5, 2, 2, 1), (9, 8, 3, 2, 1);

-- ============================================================
-- 13. 代码草稿
-- ============================================================
INSERT INTO user_code_draft (id, user_id, problem_id, language, code) VALUES
(1, 4, 5, 'cpp', '// Kadane WIP\nint maxSubArray(vector<int>& nums) {\n    int cur = 0, ans = INT_MIN;\n    for (int x : nums) {\n        cur = max(x, cur + x);\n        ans = max(ans, cur);\n    }\n    return ans;\n}'),
(2, 6, 10, 'java', '// Dijkstra draft\n// TODO: implement priority queue');

-- ============================================================
-- 14. 徽章
-- ============================================================
INSERT INTO badge (id, name, icon, description, condition_type, condition_value) VALUES
(1, '刷题新星',     'star',       '累计通过 10 道题目',        'accepted_count', 10),
(2, '刷题达人',     'fire',       '累计通过 50 道题目',        'accepted_count', 50),
(3, '竞赛新秀',     'trophy',     '首次参加比赛',              'contest_count',  1),
(4, 'AC 收割机',    'check',      '单题一次提交即 AC',         'first_try_ac',   1),
(5, '持之以恒',     'calendar',   '连续 7 天有提交记录',       'streak_days',    7);

INSERT INTO user_badge (id, user_id, badge_id, is_wearing) VALUES
(1, 4, 1, 1), (2, 4, 3, 1), (3, 6, 1, 1), (4, 6, 3, 1), (5, 6, 2, 1),
(6, 8, 1, 1), (7, 10, 1, 1);

-- ============================================================
-- 15. 课件
-- ============================================================
INSERT INTO courseware (id, teacher_id, title, url, description, privacy) VALUES
(1, 2, '第一讲：数组与哈希表',  '/slides/array-hash',   '数组遍历、双指针、哈希表的原理与应用', 1),
(2, 2, '第二讲：栈与队列',      '/slides/stack-queue',  '栈的先进后出、队列的先进先出、单调栈', 1),
(3, 2, '第三讲：动态规划入门',  '/slides/dp-intro',     'DP 三要素：状态、转移、初始化',        1);

-- 班级-课件关联
INSERT INTO class_courseware (id, class_id, courseware_id, sort, is_visible) VALUES
(1, 1, 1, 1, 1), (2, 1, 2, 2, 1), (3, 1, 3, 3, 1),
(4, 2, 3, 1, 1);

-- ============================================================
-- 16. 学生画像快照 (为 4 名学生生成上周快照)
-- ============================================================
INSERT INTO student_profile_snapshot (id, student_id, class_id, period_start, period_end, snapshot_json) VALUES
(1, 4, 1, '2024-12-16', '2024-12-22',
 '{"studentId":4,"weekOverview":{"totalSubmissions":5,"acceptedProblemCount":4,"activeDays":1},"dimensionMasteries":{"ALGORITHM":{"mastery":72},"DATA_STRUCTURE":{"mastery":65},"MATH":{"mastery":30}},"weakPoints":[{"tagName":"数论","mastery":30}]}'),
(2, 6, 1, '2024-12-16', '2024-12-22',
 '{"studentId":6,"weekOverview":{"totalSubmissions":5,"acceptedProblemCount":5,"activeDays":3},"dimensionMasteries":{"ALGORITHM":{"mastery":85},"DATA_STRUCTURE":{"mastery":78},"MATH":{"mastery":45}},"weakPoints":[{"tagName":"数论","mastery":45}]}'),
(3, 5, 1, '2024-12-16', '2024-12-22',
 '{"studentId":5,"weekOverview":{"totalSubmissions":3,"acceptedProblemCount":2,"activeDays":2},"dimensionMasteries":{"ALGORITHM":{"mastery":55},"DATA_STRUCTURE":{"mastery":50},"STRING":{"mastery":40}},"weakPoints":[{"tagName":"动态规划","mastery":40}]}'),
(4, 7, 1, '2024-12-16', '2024-12-22',
 '{"studentId":7,"weekOverview":{"totalSubmissions":3,"acceptedProblemCount":2,"activeDays":2},"dimensionMasteries":{"ALGORITHM":{"mastery":48},"DATA_STRUCTURE":{"mastery":45},"MATH":{"mastery":20}},"weakPoints":[{"tagName":"数论","mastery":20},{"tagName":"最短路","mastery":25}]}');

-- ============================================================
-- 17. 操作审计日志 (最近几次操作)
-- ============================================================
INSERT INTO audit_log (id, user_id, username, module, action, method_name, request_params, client_ip, execution_time, status) VALUES
(1, 2, 'teacher01', 'problem', 'create_problem', 'ProblemController.create', '{"title":"两数之和"}', '127.0.0.1', 150, 1),
(2, 2, 'teacher01', 'class',   'create_class',   'ClassController.create',   '{"name":"算法基础班"}', '127.0.0.1', 80, 1),
(3, 4, 'student01', 'submission', 'submit_code', 'SubmissionController.submit', '{"problemId":1,"language":"cpp"}', '127.0.0.1', 2000, 1),
(4, 4, 'student01', 'submission', 'submit_code', 'SubmissionController.submit', '{"problemId":5,"language":"cpp"}', '127.0.0.1', 1800, 1),
(5, 1, 'admin',     'user',     'reset_password','UserController.resetPassword', '{"userId":5}', '127.0.0.1', 45, 1);

SET FOREIGN_KEY_CHECKS = 1;
