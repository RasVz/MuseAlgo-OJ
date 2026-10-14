-- 题目表新增标准答案字段（AI 题库工具「标准答案生成」落库 + 「测试数据生成」用 std 实测输出）。
ALTER TABLE problem
    ADD COLUMN std_answer TEXT NULL COMMENT 'AI 生成的标准答案（C++ 代码，教师可见）' AFTER hint,
    ADD COLUMN std_explanation TEXT NULL COMMENT '标准答案的算法思路讲解' AFTER std_answer;
