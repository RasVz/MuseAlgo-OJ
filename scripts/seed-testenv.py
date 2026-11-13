#!/usr/bin/env python3
"""为开发/测试环境补充演示数据（幂等，可重复执行）。

背景
----
本地 oj_db 长期只保留一条演示主线：27 道公开题、54 条提交、2 场 2024 年的比赛。
本脚本补齐"页面能点开、图表有形态"所需的测试数据，同时修掉几处会直接让功能失效的缺口：

1. 9-15 号题的 problem_case 记录曾被删除，用例文件仍在磁盘上 —— 按原 id 恢复；
2. 42 号题既无记录也无用例文件 —— 用参考实现生成 4 组用例并落盘；
3. 177 道标题整洁、带判题用例的 FPS 题改为公开，题库从 27 题扩到 204 题；
4. 补齐 34 条缺少 submission_result 的提交，并生成 submission_case_result；
5. 新增 50 名学生、600+ 条跨两个半月的提交、4 场不同状态的比赛、试卷编程题、
   社区标签、错题本、通知与徽章。

幂等策略
--------
- 所有写入使用固定主键（用户 1001+、提交 10001+、比赛 1001+ 等）配合
  ON DUPLICATE KEY UPDATE / INSERT IGNORE，重复执行不会产生重复行；
- 比赛窗口与提交时间按"执行时刻所在小时"重算，重复执行相当于把时间线刷新到当下；
- 末尾统一对账冗余计数（submit_count / pass_count / student_count 等），
  不依赖增量维护，重复执行结果一致。

用法
----
    python3 scripts/seed-testenv.py                  # 生成 SQL 并执行
    python3 scripts/seed-testenv.py --generate-only  # 只生成 OJ-Backend/db/seed-testenv.sql
    OJ_DB_NAME=oj_test python3 scripts/seed-testenv.py

可覆盖的环境变量：OJ_DB_NAME / OJ_DB_PASS / OJ_MYSQL_CONTAINER。
"""

from __future__ import annotations

import argparse
import json
import os
import random
import subprocess
import sys
from datetime import datetime, timedelta
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SQL_OUT = REPO_ROOT / "OJ-Backend" / "db" / "seed-testenv.sql"
TESTCASE_ROOT = REPO_ROOT / "OJ-Backend" / "data" / "testcases"

DB_NAME = os.environ.get("OJ_DB_NAME", "oj_db")
DB_PASS = os.environ.get("OJ_DB_PASS", "123456")
CONTAINER = os.environ.get("OJ_MYSQL_CONTAINER", "musealgo-mysql")

RNG = random.Random(20261112)

# 统一到小时，避免同一次运行内时间漂移
NOW = datetime.now().replace(minute=0, second=0, microsecond=0)

PASSWORD_HASH = "$2b$10$zn2OJAyl9xJC9EGtotgYWe42BUfsVEs1xqORR7bUThl6fP4S.xsy2"  # 明文 123456

STUDENT_ID_BASE = 1001
SUBMISSION_ID_BASE = 10001
CONTEST_ID_BASE = 1001


# ---------------------------------------------------------------------------
# 基础设施
# ---------------------------------------------------------------------------

def q(value) -> str:
    """转成 MySQL 字符串字面量。"""
    if value is None:
        return "NULL"
    text = str(value).replace("\\", "\\\\").replace("'", "''")
    return f"'{text}'"


def dt(value: datetime) -> str:
    return q(value.strftime("%Y-%m-%d %H:%M:%S"))


def insert_block(table, columns, rows, update_columns=None, chunk=60, ignore=False):
    """拼 INSERT；update_columns 非空时用 ON DUPLICATE KEY UPDATE 保证幂等。"""
    if not rows:
        return []
    verb = "INSERT IGNORE INTO" if ignore else "INSERT INTO"
    suffix = ""
    if update_columns and not ignore:
        suffix = " ON DUPLICATE KEY UPDATE " + ", ".join(
            f"`{c}`=VALUES(`{c}`)" for c in update_columns
        )
    col_sql = ", ".join(f"`{c}`" for c in columns)
    out = []
    for start in range(0, len(rows), chunk):
        part = rows[start:start + chunk]
        values = ",\n  ".join("(" + ", ".join(r) + ")" for r in part)
        out.append(f"{verb} `{table}` ({col_sql}) VALUES\n  {values}{suffix};")
    return out


def run_sql(script: str, *, capture: bool = False):
    """在容器内执行 SQL；capture=True 时返回制表符分隔的查询结果行。"""
    cmd = [
        "docker", "exec", "-i", CONTAINER,
        "mysql", f"-uroot", f"-p{DB_PASS}",
        "--default-character-set=utf8mb4",
    ]
    if capture:
        cmd += ["-N", "-B"]
    cmd += [DB_NAME]
    proc = subprocess.run(
        cmd, input=script, text=True, capture_output=True
    )
    if proc.returncode != 0:
        raise RuntimeError(f"SQL 执行失败：\n{proc.stderr.strip()}")
    if capture:
        return [line.split("\t") for line in proc.stdout.splitlines() if line]
    return None


# ---------------------------------------------------------------------------
# 第一部分：修复失效项 + 扩展题库
# ---------------------------------------------------------------------------

# 9-15 号题被删掉的用例记录（文件仍在 OJ-Backend/data/testcases/<pid>/<caseId>/）
# 注意：原记录的 id 53-64 已被 32/33/163 号题占用，不能按原 id 回填；
# 这里改用独立 id 段，路径仍指向磁盘上原有的 <pid>/<caseId>/ 目录。
RESTORE_CASE_IDS = {
    9: range(37, 41),
    10: range(41, 45),
    11: range(45, 49),
    12: range(49, 53),
    13: range(53, 57),
    14: range(57, 61),
    15: range(61, 65),
}
RESTORE_ID_BASE = 40101


def restore_case_rows():
    """返回 (新 problem_case.id, problem_id, 磁盘用例目录 id, 顺序) 的稳定映射。"""
    rows = []
    next_id = RESTORE_ID_BASE
    for problem_id, case_ids in RESTORE_CASE_IDS.items():
        for order, case_id in enumerate(case_ids, start=1):
            rows.append((next_id, problem_id, case_id, order))
            next_id += 1
    return rows

# 42 号题（数列：斐波那契取模）新增用例
SEQ_PROBLEM_ID = 42
SEQ_CASE_IDS = list(range(40067, 40071))

SEQ_CASES = [
    ([1, 10, 100], "边界与小值"),
    ([7, 8, 9, 11, 12], "连续小值"),
    ([1000, 5000, 12345, 20000], "中段"),
    ([999999, 1000000, 499999], "上界"),
]


def fib_mod(values):
    """返回每个 a 对应的 F(a) mod 1000。"""
    out = []
    for a in values:
        if a == 1 or a == 2:
            out.append(1)
            continue
        prev, cur = 1, 1
        for _ in range(3, a + 1):
            prev, cur = cur, (prev + cur) % 1000
        out.append(cur % 1000)
    return out


def write_seq_testcase_files():
    """把 42 号题的用例文件写到磁盘，判题机读取的就是这份目录。"""
    for case_id, (values, _) in zip(SEQ_CASE_IDS, SEQ_CASES):
        case_dir = TESTCASE_ROOT / str(SEQ_PROBLEM_ID) / str(case_id)
        case_dir.mkdir(parents=True, exist_ok=True)
        inputs = " ".join(str(v) for v in values)
        expected = fib_mod(values)
        (case_dir / "input.txt").write_text(
            f"{len(values)}\n" + "\n".join(str(v) for v in values) + "\n",
            encoding="utf-8",
        )
        (case_dir / "output.txt").write_text(
            "\n".join(str(v) for v in expected) + "\n", encoding="utf-8"
        )


def build_part1():
    lines = [
        "-- ============================================================",
        "-- 第一部分：修复失效项与扩展题库",
        "-- ============================================================",
        "",
        "-- 1) 恢复 9-15 号题被删除的 28 条判题用例记录（用例文件仍在 data/testcases/）",
    ]
    rows = []
    for new_id, problem_id, case_id, order in restore_case_rows():
        rows.append([
            str(new_id), str(problem_id), str(order),
            q(f"{problem_id}/{case_id}/input.txt"),
            q(f"{problem_id}/{case_id}/output.txt"),
            "25", "0", "1",
        ])
    lines += insert_block(
        "problem_case",
        ["id", "problem_id", "case_order", "input_file_path",
         "output_file_path", "score", "case_type", "status"],
        rows, update_columns=["problem_id", "case_order", "input_file_path",
                              "output_file_path", "score", "case_type", "status"],
    )

    lines += ["", "-- 2) 42 号题（数列）新增 4 组用例，文件由本脚本写入 data/testcases/42/"]
    rows = []
    for order, case_id in enumerate(SEQ_CASE_IDS, start=1):
        rows.append([
            str(case_id), str(SEQ_PROBLEM_ID), str(order),
            q(f"{SEQ_PROBLEM_ID}/{case_id}/input.txt"),
            q(f"{SEQ_PROBLEM_ID}/{case_id}/output.txt"),
            "25", "0", "1",
        ])
    lines += insert_block(
        "problem_case",
        ["id", "problem_id", "case_order", "input_file_path",
         "output_file_path", "score", "case_type", "status"],
        rows, update_columns=["case_order", "score", "case_type", "status"],
    )

    lines += [
        "",
        "-- 3) 公开 177 道标题整洁、带判题用例的 FPS 题（题库 27 -> 204）",
        "--    没有用例的题不公开，否则学生提交后无法判题。",
        "--    回滚：UPDATE problem SET is_public=0 WHERE source LIKE 'FPS%' AND MOD(id,3)=0;",
        """UPDATE `problem` p
SET p.is_public = 1
WHERE p.is_deleted = 0
  AND p.is_public = 0
  AND p.source LIKE 'FPS%'
  AND p.title NOT LIKE '%&nbsp;%'
  AND p.title NOT LIKE '%#%'
  AND CHAR_LENGTH(p.title) <= 40
  AND MOD(p.id, 3) = 0
  AND EXISTS (SELECT 1 FROM `problem_case` c WHERE c.problem_id = p.id);""",
        "",
        "-- 4) 给这批题分配难度档位。这是合成值：FPS 导入时 difficulty 全部落成默认 800，",
        "--    不区分档位就无法验证难度筛选与排序。按 id 取模分布，重复执行结果一致。",
        "--    回滚：UPDATE problem SET difficulty=800 WHERE source LIKE 'FPS%' AND MOD(id,3)=0;",
        """UPDATE `problem`
SET difficulty = CASE MOD(id DIV 3, 8)
        WHEN 0 THEN 800  WHEN 1 THEN 1000 WHEN 2 THEN 1200 WHEN 3 THEN 1400
        WHEN 4 THEN 1600 WHEN 5 THEN 1800 WHEN 6 THEN 2000 ELSE 2200 END
WHERE source LIKE 'FPS%' AND is_public = 1 AND MOD(id, 3) = 0;""",
        "",
        "-- 5) 补齐历史提交缺失的 submission_result（原本 54 条提交只有 20 条结果）",
        """INSERT INTO `submission_result`
    (submission_id, status, score, run_time, run_memory, judged_at)
SELECT s.id,
       COALESCE(s.status, 0),
       COALESCE(s.score, 0),
       COALESCE(s.run_time, 0),
       COALESCE(s.run_memory, 0),
       COALESCE(s.create_time, NOW())
FROM `submission` s
LEFT JOIN `submission_result` r ON r.submission_id = s.id
WHERE r.submission_id IS NULL;""",
        "",
    ]
    return lines


def fetch_problem_pool():
    """取可用作提交对象的题目：公开 + 有计分用例。"""
    # 注意：这里用"执行 part1 之后的口径"查询，即使 part1 尚未执行也能得到同一集合
    sql = """
SELECT p.id,
       (SELECT COUNT(*) FROM problem_case c
         WHERE c.problem_id = p.id AND c.case_type = 0) AS cases
FROM `problem` p
WHERE p.is_deleted = 0
  AND (p.is_public = 1
       OR (p.source LIKE 'FPS%'
           AND p.title NOT LIKE '%&nbsp;%'
           AND p.title NOT LIKE '%#%'
           AND CHAR_LENGTH(p.title) <= 40
           AND MOD(p.id, 3) = 0))
ORDER BY p.id;
"""
    rows = run_sql(sql, capture=True)
    pending_case_ids = set(RESTORE_CASE_IDS) | {SEQ_PROBLEM_ID}
    pool = []
    for row in rows:
        problem_id, cases = int(row[0]), int(row[1])
        if cases > 0 or problem_id in pending_case_ids:
            pool.append(problem_id)
    if len(pool) < 50:
        raise RuntimeError(f"可用题目只有 {len(pool)} 道，题库状态异常，请先检查 oj_db")
    return pool


# ---------------------------------------------------------------------------
# 第二部分：规模与完整度数据
# ---------------------------------------------------------------------------

STUDENT_NAMES = [
    "陈嘉禾", "林予安", "赵慕白", "周星野", "吴清和", "郑望舒", "孙照野", "李知远",
    "王砚舟", "徐南乔", "胡砚秋", "朱明溪", "高见山", "何听澜", "郭子瑜", "马长风",
    "罗时雨", "梁思齐", "宋聿明", "唐知微", "许砚白", "韩青岚", "冯岸秋", "邓斯年",
    "曹温言", "彭翊之", "曾映川", "萧穆清", "田禾生", "董观澜", "袁叙白", "潘令仪",
    "于归远", "蒋叙宁", "杜明野", "叶从舟", "程见山", "苏亦然", "魏书宁", "吕照临",
    "丁怀瑾", "沈度白", "姚听雪", "卢承宇", "蔡行知", "贾砚生", "崔景行", "谭星禾",
    "陆问津", "范知白",
]

SCHOOLS = ["第一中学", "第二中学", "第三中学"]
CLASS_MAJORS = ["高一(3)班", "高二(1)班", "初三(5)班"]

# 班级 -> 学生下标区间
CLASS_ASSIGNMENT = {1: (0, 22), 2: (22, 42), 3: (42, 50)}

# 各班作业题（与 build_class_content 中的 type=4 题单一致），
# 保证作业状态页与班级学情有足够的提交覆盖。
HOMEWORK_PRIORITY = {1: [1, 2, 4, 8], 2: [10, 11, 15, 5], 3: []}
HOMEWORK_WINDOW_DAYS = 21

CODE_CPP = [
    "#include <bits/stdc++.h>\nusing namespace std;\nint main() {\n    ios::sync_with_stdio(false);\n    cin.tie(nullptr);\n    // problem @PID@ / @TAG@\n    return 0;\n}",
    "#include <bits/stdc++.h>\nusing namespace std;\nint n, a[100005];\nint main() {\n    cin >> n;\n    for (int i = 0; i < n; ++i) cin >> a[i];\n    // problem @PID@ / @TAG@\n    return 0;\n}",
    "#include <cstdio>\nint main() {\n    // problem @PID@ / @TAG@\n    return 0;\n}",
]
CODE_PY = [
    "import sys\n\ndef main():\n    data = sys.stdin.read().split()\n    # problem @PID@ / @TAG@\n\nmain()",
    "n = int(input())\narr = list(map(int, input().split()))\n# problem @PID@ / @TAG@\nprint(sum(arr))",
]
CODE_JAVA = [
    "import java.util.*;\n\npublic class Main {\n    public static void main(String[] args) {\n        // problem @PID@ / @TAG@\n    }\n}",
]
CODE_C = [
    "#include <stdio.h>\nint main(void) {\n    /* problem @PID@ / @TAG@ */\n    return 0;\n}",
]

LANGUAGES = [
    ("cpp", 0.55, CODE_CPP),
    ("python", 0.25, CODE_PY),
    ("java", 0.15, CODE_JAVA),
    ("c", 0.05, CODE_C),
]

TAG_NOTES = ["AC", "WA", "TLE", "CE", "RE", "暴力", "正解", "重写"]


def pick_language():
    roll = RNG.random()
    acc = 0.0
    for name, weight, pool in LANGUAGES:
        acc += weight
        if roll <= acc:
            return name, pool
    return LANGUAGES[0][0], LANGUAGES[0][2]


def build_students():
    """50 名学生 + RBAC 关联 + 班级成员。"""
    user_rows, role_rows, member_rows = [], [], []
    member_id = 1001
    for idx, name in enumerate(STUDENT_NAMES):
        uid = STUDENT_ID_BASE + idx
        rating = 1050 + (idx * 37) % 700
        level = max(1, min(9, rating // 180))
        experience = (rating - 1000) * 4
        created = NOW - timedelta(days=75) + timedelta(hours=idx * 5)
        user_rows.append([
            str(uid), q(f"stu{uid}"), q(PASSWORD_HASH), q(name), "1", "0",
            q(f"stu{uid}@test.local"), "NULL",
            q(name), q(SCHOOLS[idx % 3]), q(CLASS_MAJORS[idx % 3]),
            str(level), str(experience), str(rating),
            dt(created),
        ])
        role_rows.append([str(uid), "1"])

    for class_id, (start, end) in CLASS_ASSIGNMENT.items():
        for idx in range(start, end):
            member_rows.append([
                str(member_id), str(class_id), str(STUDENT_ID_BASE + idx),
                dt(NOW - timedelta(days=70) + timedelta(hours=member_id % 240)),
                q(STUDENT_NAMES[idx]),
            ])
            member_id += 1

    lines = ["-- 5) 50 名测试学生（口令同为 123456，邮箱域 .test.local）"]
    lines += insert_block(
        "user",
        ["id", "username", "password", "nickname", "role", "is_banned", "email",
         "avatar", "real_name", "school", "major", "level", "experience",
         "rating", "create_time"],
        user_rows,
        update_columns=["nickname", "school", "major", "level", "experience", "rating"],
    )
    lines.append("")
    lines += insert_block("sys_user_role", ["user_id", "role_id"], role_rows, ignore=True)
    lines.append("")
    lines += insert_block(
        "class_student",
        ["id", "class_id", "student_id", "join_time", "remark"],
        member_rows,
        update_columns=["class_id", "student_id", "remark"],
    )
    lines.append("")
    return lines, [STUDENT_ID_BASE + i for i in range(len(STUDENT_NAMES))]


def plan_submissions(pool):
    """按"学生能力 + 题目集合"生成提交计划。返回 dict 列表。"""
    core = [p for p in pool if p <= 15 or 4000 <= p <= 4008 or p == 58 or p == 32]
    others = [p for p in pool if p not in core]
    plans = []
    for idx, name in enumerate(STUDENT_NAMES):
        uid = STUDENT_ID_BASE + idx
        rating = 1050 + (idx * 37) % 700
        # 活跃学生做更多题；刻意留一部分低活跃学生，让排名与热力图有层次
        target = 5 + (idx * 7) % 11  # 5-15 道题
        chosen = RNG.sample(core, k=min(len(core), max(3, int(target * 0.65))))
        rest = target - len(chosen)
        if rest > 0 and others:
            chosen += RNG.sample(others, k=min(len(others), rest))

        # 本班作业题至少覆盖 2 道，否则作业状态页会大片空白
        student_class = next(
            (cid for cid, (s, e) in CLASS_ASSIGNMENT.items() if s <= idx < e), None
        )
        priority = [p for p in HOMEWORK_PRIORITY.get(student_class, []) if p in pool]
        if priority:
            start = idx % len(priority)
            forced = [priority[(start + k) % len(priority)] for k in range(min(2, len(priority)))]
            chosen = list(dict.fromkeys(forced + chosen))

        # 学生在 2 个月里集中活跃 6-14 天
        active_days = RNG.sample(range(0, 72), k=RNG.randint(6, 14))
        for problem_id in chosen:
            ac_first = 0.22 + (rating - 1050) / 1500.0
            attempts = 1
            # 作业题优先落在作业窗口内，其余按活跃日分布
            if problem_id in priority and RNG.random() < 0.8:
                day = RNG.randint(0, HOMEWORK_WINDOW_DAYS - 1)
            else:
                day = RNG.choice(active_days)
            ts = NOW - timedelta(days=day) + timedelta(
                hours=RNG.randint(9, 22), minutes=RNG.randint(0, 59)
            )
            if ts > NOW - timedelta(minutes=30):
                ts = NOW - timedelta(minutes=30)
            solved = False
            for attempt in range(1, 5):
                if attempt == 1:
                    solved = RNG.random() < ac_first
                else:
                    solved = RNG.random() < (ac_first + 0.12 * attempt)
                if solved:
                    status = 1
                else:
                    status = RNG.choices(
                        [2, 5, 4, 3], weights=[62, 14, 9, 15], k=1
                    )[0]
                plans.append({
                    "user_id": uid,
                    "problem_id": problem_id,
                    "status": status,
                    "create_time": ts,
                    "attempt": attempt,
                    "contest_id": None,
                })
                if solved:
                    break
                ts = ts + timedelta(minutes=RNG.randint(3, 90))
                if ts > NOW - timedelta(minutes=20):
                    break

    # 追加错题样本：一半学生各留 2 道反复 WA 的题，
    # 让错题本（user_problem_review）按应用自身的收录规则有稳定内容。
    solved = {}
    for plan in plans:
        if plan["status"] == 1:
            solved.setdefault(plan["user_id"], set()).add(plan["problem_id"])
    for idx in range(0, len(STUDENT_NAMES), 2):
        uid = STUDENT_ID_BASE + idx
        candidates = [p for p in others if p not in solved.get(uid, set())]
        if not candidates:
            continue
        base_ts = NOW - timedelta(days=RNG.randint(12, 60), hours=RNG.randint(0, 8))
        for problem_id in RNG.sample(candidates, k=min(2, len(candidates))):
            for attempt in range(2):
                plans.append({
                    "user_id": uid,
                    "problem_id": problem_id,
                    "status": RNG.choice([2, 2, 5]),
                    "create_time": base_ts + timedelta(minutes=40 * attempt),
                    "attempt": attempt + 1,
                    "contest_id": None,
                })
    return plans


def build_submissions(plans, case_map, start_id=SUBMISSION_ID_BASE,
                      start_case_id=100001, header=None):
    sub_rows, code_rows, result_rows, case_rows = [], [], [], []
    sub_id = start_id
    case_result_id = start_case_id
    for plan in plans:
        language, templates = pick_language()
        problems_cases = case_map.get(plan["problem_id"], [])
        status = plan["status"]
        score = 100 if status == 1 else 0
        run_time = (
            RNG.randint(8, 60) if status == 1
            else RNG.randint(1000, 2000) if status == 5
            else RNG.randint(4, 48)
        )
        run_memory = RNG.randint(8000, 42000) if status != 4 else RNG.randint(2000, 6000)
        template = RNG.choice(templates)
        tag = RNG.choice(TAG_NOTES)
        code = template.replace("@PID@", str(plan["problem_id"])).replace("@TAG@", tag)
        error_message = None
        if status == 3:
            error_message = "main.cpp:12:5: error: expected ';' before '}' token"
        elif status == 4:
            error_message = "运行时错误：Segmentation fault (core dumped)"

        sub_rows.append([
            str(sub_id), str(plan["problem_id"]), str(plan["user_id"]),
            q(language), str(score), str(status), str(run_time), str(run_memory),
            dt(plan["create_time"]),
            q("FAILED" if status == 3 else "FINISHED"),
            "NULL" if plan["contest_id"] is None else str(plan["contest_id"]),
            "NULL" if plan.get("vp_attempt_id") is None else str(plan["vp_attempt_id"]),
            q(error_message),
        ])
        code_rows.append([str(sub_id), q(code)])
        result_rows.append([
            str(sub_id), str(status), str(score), str(run_time), str(run_memory),
            dt(plan["create_time"] + timedelta(seconds=RNG.randint(1, 4))),
        ])

        # 逐点结果：AC 全通过；其余在第一个失败用例处停止（与真实判题一致）
        if status in (1, 2, 4, 5) and problems_cases:
            for order, (case_id, case_order) in enumerate(problems_cases[:10], start=1):
                if status == 1:
                    case_status = 1
                elif order == 1:
                    case_status = status
                else:
                    break
                case_rows.append([
                    str(case_result_id), str(sub_id), str(case_id),
                    str(case_order), str(case_status),
                    str(run_time if case_status == 1 else RNG.randint(3, 60)),
                    str(run_memory),
                    "NULL" if case_status == 1 else q(error_message or "输出与期望不符"),
                ])
                case_result_id += 1
        sub_id += 1

    lines = []
    if header:
        lines.append(header)
    lines += insert_block(
        "submission",
        ["id", "problem_id", "user_id", "language", "score", "status",
         "run_time", "run_memory", "create_time", "judge_phase", "contest_id",
         "vp_attempt_id", "error_message"],
        sub_rows,
        update_columns=["problem_id", "user_id", "language", "score", "status",
                        "run_time", "run_memory", "create_time", "judge_phase",
                        "contest_id", "vp_attempt_id", "error_message"],
    )
    lines.append("")
    lines += insert_block(
        "submission_code", ["submission_id", "code"], code_rows,
        update_columns=["code"],
    )
    lines.append("")
    lines += insert_block(
        "submission_result",
        ["submission_id", "status", "score", "run_time", "run_memory", "judged_at"],
        result_rows,
        update_columns=["status", "score", "run_time", "run_memory", "judged_at"],
    )
    lines.append("")
    # 逐点结果是完全派生数据：先清掉本脚本生成的区间，避免提交计划变化后留下孤儿行
    lines.append(
        "DELETE FROM `submission_case_result` "
        f"WHERE submission_id BETWEEN {start_id} AND {sub_id - 1};"
    )
    lines += insert_block(
        "submission_case_result",
        ["id", "submission_id", "problem_case_id", "case_order", "status",
         "run_time", "run_memory", "error_message"],
        case_rows,
        update_columns=["submission_id", "problem_case_id", "case_order", "status",
                        "run_time", "run_memory", "error_message"],
    )
    lines.append("")
    return lines, sub_id - 1, case_result_id - 1


def at_hour(base: datetime, hour: int, minute: int = 0) -> datetime:
    """把日期对齐到指定时刻，避免比赛落在深夜。"""
    return base.replace(hour=hour, minute=minute, second=0, microsecond=0)


def student_rating(uid: int) -> int:
    """与 build_students 中的 rating 公式保持一致。"""
    return 1050 + ((uid - STUDENT_ID_BASE) * 37) % 700


CONTEST_LEVELS = [
    # id, code, name, min, max, enabled, sort_order, description
    (1001, "BASIC", "基础赛", 0, 1300, 1, 1, "面向 Rating 1300 以下的入门正式赛"),
    (1002, "INTERMEDIATE", "进阶赛", 1300, 1500, 1, 2, "面向 1300-1499 的常规正式赛"),
    (1003, "ADVANCED", "高级赛", 1500, 1800, 1, 3, "面向 1500-1799 的进阶正式赛"),
    (1004, "ELITE", "精英赛", 1800, None, 1, 4, "面向 1800 及以上选手，不设上限"),
    (1005, "INVITATIONAL", "邀请赛", 1900, None, 0, 5, "按名单邀请，暂未启用"),
]


def build_contest_levels():
    rows = [
        [str(lid), q(code), q(name),
         "NULL" if lo is None else str(lo),
         "NULL" if hi is None else str(hi),
         str(en), str(sort), q(desc), dt(NOW - timedelta(days=120))]
        for lid, code, name, lo, hi, en, sort, desc in CONTEST_LEVELS
    ]
    lines = ["-- 7) 比赛等级配置：4 个启用等级 + 1 个停用示例"]
    lines += insert_block(
        "contest_level",
        ["id", "code", "name", "min_rating", "max_rating", "enabled",
         "sort_order", "description", "create_time"],
        rows,
        update_columns=["code", "name", "min_rating", "max_rating", "enabled",
                        "sort_order", "description"],
    )
    lines.append("")
    return lines


def participants_in_band(lo, hi, count):
    """按 Rating 区间挑选参赛者，保证报名记录与等级限制自洽。"""
    candidates = [STUDENT_ID_BASE + i for i in range(len(STUDENT_NAMES))]
    picked = [
        uid for uid in candidates
        if (lo is None or student_rating(uid) >= lo)
        and (hi is None or student_rating(uid) < hi)
    ]
    return picked[:count]


def build_contests(pool):
    """4 场比赛：报名审核中 / 进行中 / 已结束 OI / 班级赛，各自绑定比赛等级。"""
    core = [p for p in pool if p <= 15 or 4000 <= p <= 4008]
    if len(core) < 12:
        core = pool[:24]

    basic = participants_in_band(0, 1300, 12)
    intermediate = participants_in_band(1300, 1500, 12)
    advanced = participants_in_band(1500, 1800, 14)
    class_one = [STUDENT_ID_BASE + i for i in range(0, 20)]

    contests = [
        {
            "id": CONTEST_ID_BASE,
            "title": "2026 秋季基础赛 第 1 场（报名审核中）",
            "description": "面向 Rating 1300 以下选手的入门正式赛，报名需教师审核。ACM 赛制，共 4 题。",
            "start": at_hour(NOW + timedelta(days=3), 14),
            "duration": 120,
            "type": "Public", "mode": "ACM", "category": "FORMAL",
            "registration_review": 1, "class_id": None,
            "level": ("BASIC", 0, 1300),
            "problems": core[:4],
            "participants": basic,
            "statuses": (["PENDING"] * 6 + ["APPROVED"] * 4
                         + ["REJECTED"] * max(0, len(basic) - 10)),
            "finalized": False,
        },
        {
            "id": CONTEST_ID_BASE + 1,
            "title": "2026 秋季进阶赛 第 3 场（进行中）",
            "description": "每周联赛第 3 场，ACM 赛制，赛后按 Rating 结算。",
            "start": NOW - timedelta(hours=1),
            "duration": 240,
            "type": "Public", "mode": "ACM", "category": "FORMAL",
            "registration_review": 0, "class_id": None,
            "level": ("INTERMEDIATE", 1300, 1500),
            "problems": core[4:9],
            "participants": intermediate,
            "statuses": ["AUTO_APPROVED"] * len(intermediate),
            "finalized": False,
        },
        {
            "id": CONTEST_ID_BASE + 2,
            "title": "2026 高级赛 校选模拟（OI 赛制，已结束）",
            "description": "OI 赛制模拟赛，按最后一次提交计分，赛后已结算 Rating。",
            "start": at_hour(NOW - timedelta(days=16), 19),
            "duration": 180,
            "type": "Public", "mode": "OI", "category": "FORMAL",
            "registration_review": 0, "class_id": None,
            "level": ("ADVANCED", 1500, 1800),
            "problems": core[9:14],
            "participants": advanced,
            "statuses": ["AUTO_APPROVED"] * len(advanced),
            "finalized": True,
        },
        {
            "id": CONTEST_ID_BASE + 3,
            "title": "算法基础班 第 6 周班内赛（已结束）",
            "description": "班级内部练习赛，仅本班学生可参加；班级赛不设 Rating 限制。",
            "start": at_hour(NOW - timedelta(days=9), 15),
            "duration": 120,
            "type": "Class", "mode": "ACM", "category": "FORMAL",
            "registration_review": 0, "class_id": 1,
            "level": None,
            "problems": core[14:18],
            "participants": class_one,
            "statuses": ["AUTO_APPROVED"] * len(class_one),
            "finalized": True,
        },
    ]

    contest_rows, problem_rows, user_rows = [], [], []
    rank_rows, rating_rows = [], []
    rank_id, rating_id = 1001, 1001

    for c in contests:
        end = c["start"] + timedelta(minutes=c["duration"])
        level = c["level"]
        contest_rows.append([
            str(c["id"]), q(c["title"]), q(c["description"]),
            dt(c["start"]), dt(end), str(c["duration"]),
            q(c["type"]), q(c["mode"]), q(c["category"]),
            q(level[0]) if level else "NULL",
            str(c["registration_review"]),
            "NULL" if not level or level[1] is None else str(level[1]),
            "NULL" if not level or level[2] is None else str(level[2]),
            "2", q("张老师"), str(len(c["participants"])),
            dt(NOW - timedelta(days=20)),
            "NULL" if c["class_id"] is None else str(c["class_id"]),
            "1", dt(end) if c["finalized"] else "NULL",
        ])
        for order, problem_id in enumerate(c["problems"], start=1):
            problem_rows.append([
                str(c["id"] * 100 + order), str(c["id"]), str(problem_id),
                str(order), "100",
            ])
        for offset, (user_id, status) in enumerate(zip(c["participants"], c["statuses"])):
            user_rows.append([
                str(c["id"] * 1000 + offset), str(c["id"]), str(user_id),
                q(status), str(student_rating(user_id)),
                "1" if status in ("AUTO_APPROVED", "APPROVED") else "0",
                "NULL", "NULL",
                q("报名信息不完整") if status == "REJECTED" else "NULL",
                dt(c["start"] - timedelta(days=2)),
            ])

        if not c["finalized"]:
            continue

        # 已结束的比赛：结算排名 + Rating 变化
        total_problems = len(c["problems"])
        scored = []
        for offset, user_id in enumerate(c["participants"]):
            # 参赛者解题数呈梯度分布，保证排名首位不是 0 题
            solved = max(0, total_problems - (offset % (total_problems + 1)))
            penalty = solved * 35 + 10 + (offset * 11) % 120
            scored.append((user_id, solved, penalty))
        scored.sort(key=lambda x: (-x[1], x[2]))
        for rank, (user_id, solved, penalty) in enumerate(scored, start=1):
            before = student_rating(user_id)
            delta = max(-30, 60 - rank * 5)
            after = before + delta
            rank_rows.append([
                str(rank_id), str(c["id"]), str(user_id), str(rank),
                str(solved), str(penalty), str(solved * 100),
                q("OFFICIAL"), q("v1"), str(before), str(after),
                q("一等奖" if rank <= 2 else "二等奖" if rank <= 5 else None),
                "1", dt(end),
            ])
            rating_rows.append([
                str(rating_id), str(user_id), str(c["id"]), str(after),
                str(delta), str(rank), dt(end),
            ])
            rank_id += 1
            rating_id += 1

    lines = [
        "-- 8) 四场比赛：报名审核中 / 进行中 / 已结束 OI / 班级赛",
        "--    参赛者按等级区间重算，先清掉本脚本旧行，避免 (contest_id,user_id) 唯一键冲突",
        "DELETE FROM `contest_user` WHERE contest_id BETWEEN 1001 AND 1099;",
        "DELETE FROM `contest_rank` WHERE contest_id BETWEEN 1001 AND 1099;",
        "DELETE FROM `problem_contest` WHERE contest_id BETWEEN 1001 AND 1099;",
        "DELETE FROM `user_rating` WHERE contest_id BETWEEN 1001 AND 1099;",
        "",
    ]
    lines += insert_block(
        "contest",
        ["id", "title", "description", "start_time", "end_time", "duration",
         "type", "mode", "category", "level_code", "registration_review",
         "rating_min", "rating_max", "author_id", "author_name",
         "participant_count", "create_time", "class_id", "visible",
         "rank_finalized_at"],
        contest_rows,
        update_columns=["title", "description", "start_time", "end_time", "duration",
                        "type", "mode", "category", "level_code", "registration_review",
                        "rating_min", "rating_max", "participant_count", "class_id",
                        "visible", "rank_finalized_at"],
    )
    lines.append("")
    lines += insert_block(
        "problem_contest",
        ["id", "contest_id", "problem_id", "problem_order", "score"],
        problem_rows, update_columns=["contest_id", "problem_id", "problem_order", "score"],
    )
    lines.append("")
    lines += insert_block(
        "contest_user",
        ["id", "contest_id", "user_id", "status", "rating_at_registration",
         "eligible", "reviewed_by", "review_time", "reject_reason", "create_time"],
        user_rows,
        update_columns=["contest_id", "user_id", "status", "rating_at_registration",
                        "eligible", "reject_reason"],
    )
    lines.append("")
    lines += insert_block(
        "contest_rank",
        ["id", "contest_id", "user_id", "rank_num", "solved_count", "penalty_time",
         "total_score", "participant_type", "algorithm_version", "rating_before",
         "rating_after", "award", "is_visible", "create_time"],
        rank_rows,
        update_columns=["rank_num", "solved_count", "penalty_time", "total_score",
                        "rating_before", "rating_after", "award"],
    )
    lines.append("")
    lines += insert_block(
        "user_rating",
        ["id", "user_id", "contest_id", "rating", "rating_change", "rank", "create_time"],
        rating_rows,
        update_columns=["rating", "rating_change", "rank"],
    )
    lines.append("")
    return lines, contests

def build_contest_submissions(contests):
    """给每场比赛补赛内提交，让赛内提交表与实时排行有内容。"""
    plans = []
    for c in contests:
        if c["id"] == CONTEST_ID_BASE:  # 未开始，无提交
            continue
        end = min(c["start"] + timedelta(minutes=c["duration"]), NOW - timedelta(minutes=5))
        span = (end - c["start"]).total_seconds()
        if span <= 120:
            continue
        for offset, user_id in enumerate(c["participants"][:12]):
            for order, problem_id in enumerate(c["problems"][:2], start=1):
                ts = c["start"] + timedelta(seconds=RNG.uniform(60, span))
                status = 1 if (offset + order) % 3 else 2
                plans.append({
                    "user_id": user_id,
                    "problem_id": problem_id,
                    "status": status,
                    "create_time": ts,
                    "attempt": 1,
                    "contest_id": c["id"],
                })
    return plans


EXAM_PAPERS = [
    {
        "id": 1001,
        "title": "GESP C++ 2级 2025-09 真题",
        "category": "GESP",
        "level": "2级",
        "duration": 90,
        "published": 1,
        "description": "覆盖变量、运算符、分支循环、数组与函数基础，适合 2 级备考自测。",
        "problems": [],
        "questions": [
            ("SINGLE", "下列变量声明中，合法的是？",
             [("A", "int 2a;"), ("B", "int a-1;"), ("C", "int _count;"), ("D", "int for;")],
             "C", "变量名只能由字母、数字、下划线组成，且不能以数字开头，也不能是关键字。", 5),
            ("SINGLE", "表达式 17 / 5 的值是？",
             [("A", "3"), ("B", "3.4"), ("C", "4"), ("D", "2")],
             "A", "两个 int 相除执行整数除法，结果向下取整为 3。", 5),
            ("SINGLE", "表达式 17 % 5 的值是？",
             [("A", "2"), ("B", "3"), ("C", "3.4"), ("D", "0")],
             "A", "17 = 5 * 3 + 2，取余结果为 2。", 5),
            ("SINGLE", "在 C++ 中，用来向屏幕输出内容的是？",
             [("A", "cin"), ("B", "cout"), ("C", "scanf"), ("D", "fopen")],
             "B", "cout 配合 << 用于标准输出，cin 配合 >> 用于输入。", 5),
            ("SINGLE", "字符 'A' 的 ASCII 码值是？",
             [("A", "61"), ("B", "65"), ("C", "97"), ("D", "41")],
             "B", "'A' 为 65，'a' 为 97，两者相差 32。", 5),
            ("SINGLE", "下列运算符中表示逻辑与的是？",
             [("A", "&"), ("B", "&&"), ("C", "|"), ("D", "||")],
             "B", "&& 是逻辑与，|| 是逻辑或；& 与 | 是按位运算符。", 5),
            ("SINGLE", "执行 int a = 3; a += 2; 之后，a 的值是？",
             [("A", "2"), ("B", "3"), ("C", "5"), ("D", "6")],
             "C", "a += 2 等价于 a = a + 2，结果为 5。", 5),
            ("SINGLE", "声明 int a[10]; 之后，合法下标的最大值是？",
             [("A", "9"), ("B", "10"), ("C", "11"), ("D", "0")],
             "A", "数组下标从 0 开始，长度为 10 时合法范围是 0 到 9。", 5),
            ("SINGLE", "for (int i = 0; i < 5; i++) 的循环体执行次数是？",
             [("A", "4"), ("B", "5"), ("C", "6"), ("D", "0")],
             "B", "i 取 0、1、2、3、4，共 5 次。", 5),
            ("SINGLE", "下列哪个头文件提供 cin 与 cout？",
             [("A", "<cmath>"), ("B", "<cstdio>"), ("C", "<iostream>"), ("D", "<cstring>")],
             "C", "<iostream> 是 C++ 标准输入输出流头文件。", 5),
            ("SINGLE", "while (0) { ... } 的循环体执行次数是？",
             [("A", "0"), ("B", "1"), ("C", "死循环"), ("D", "不确定")],
             "A", "条件为 0 即为假，循环体一次都不会执行。", 5),
            ("SINGLE", "下列哪个是 C++ 的布尔字面量？",
             [("A", "True"), ("B", "TRUE"), ("C", "true"), ("D", "yes")],
             "C", "C++ 的布尔字面量是小写的 true 与 false。", 5),
            ("JUDGE", "C++ 中数组下标从 0 开始。", None,
             "T", "例如 int a[3] 的元素是 a[0]、a[1]、a[2]。", 5),
            ("JUDGE", "int 类型可以直接存储小数部分。", None,
             "F", "int 只能存储整数，小数会被截断；存小数应使用 double 或 float。", 5),
            ("JUDGE", "= 是赋值运算符，== 是相等比较运算符。", None,
             "T", "if (a = 1) 是赋值，if (a == 1) 才是比较，这是常见笔误。", 5),
            ("JUDGE", "continue 语句会结束整个循环。", None,
             "F", "continue 只跳过本次迭代剩余部分，break 才结束整个循环。", 5),
            ("JUDGE", "函数可以先声明再定义。", None,
             "T", "先写函数原型即可在定义之前调用。", 5),
            ("JUDGE", "字符串 \"123\" 和整数 123 在 C++ 中是同一种类型。", None,
             "F", "前者是字符串字面量，后者是整型常量，类型不同。", 5),
            ("JUDGE", "break 只能用于循环或 switch 语句中。", None,
             "T", "在循环与 switch 之外使用 break 会编译错误。", 5),
            ("JUDGE", "未初始化的局部变量一定等于 0。", None,
             "F", "局部变量未初始化时值是不确定的；全局变量才会默认初始化为 0。", 5),
        ],
    },
    {
        "id": 1002,
        "title": "CSP-J 2025 初赛模拟卷",
        "category": "CSP_J",
        "level": "初赛",
        "duration": 120,
        "published": 1,
        "description": "按 CSP-J 初赛题型组织：单项选择、多项选择与判断，覆盖复杂度、数据结构与图论基础。",
        "problems": [],
        "questions": [
            ("SINGLE", "二分查找在有序序列上的平均时间复杂度是？",
             [("A", "O(n)"), ("B", "O(log n)"), ("C", "O(n log n)"), ("D", "O(1)")],
             "B", "每次将候选区间折半，比较次数为 log2(n) 量级。", 5),
            ("SINGLE", "栈的基本特点是？",
             [("A", "先进先出"), ("B", "后进先出"), ("C", "随机存取"), ("D", "双端插入")],
             "B", "栈只在栈顶插入和删除，因此后进先出。", 5),
            ("SINGLE", "队列的基本特点是？",
             [("A", "先进先出"), ("B", "后进先出"), ("C", "只能从尾部删除"), ("D", "随机存取")],
             "A", "队列在队尾入队、队头出队，先进先出。", 5),
            ("SINGLE", "Dijkstra 算法不能正确处理下列哪种情况？",
             [("A", "有向图"), ("B", "无向图"), ("C", "存在负权边"), ("D", "稠密图")],
             "C", "Dijkstra 依赖已确定点的距离不会再变小，负权边会破坏这一前提。", 5),
            ("SINGLE", "快速排序的最坏时间复杂度是？",
             [("A", "O(n)"), ("B", "O(n log n)"), ("C", "O(n^2)"), ("D", "O(log n)")],
             "C", "每次划分极不均衡时退化为 O(n^2)。", 5),
            ("SINGLE", "1 KiB 等于多少字节？",
             [("A", "1000"), ("B", "1024"), ("C", "512"), ("D", "2048")],
             "B", "1 KiB = 2^10 = 1024 字节。", 5),
            ("SINGLE", "十进制 13 对应的二进制是？",
             [("A", "1011"), ("B", "1100"), ("C", "1101"), ("D", "1110")],
             "C", "13 = 8 + 4 + 1 = 1101(2)。", 5),
            ("SINGLE", "对 n 个元素做冒泡排序，最坏情况下的比较次数是？",
             [("A", "n"), ("B", "n-1"), ("C", "n(n-1)/2"), ("D", "n^2/2 + n")],
             "C", "最坏需要 n-1 轮，第 i 轮比较 n-i 次，合计 n(n-1)/2。", 5),
            ("SINGLE", "n 个顶点的无向图用邻接矩阵存储，空间复杂度是？",
             [("A", "O(n)"), ("B", "O(n^2)"), ("C", "O(m)"), ("D", "O(n log n)")],
             "B", "邻接矩阵是 n×n 的二维表。", 5),
            ("SINGLE", "中缀表达式转后缀表达式通常借助哪种结构？",
             [("A", "队列"), ("B", "栈"), ("C", "堆"), ("D", "树")],
             "B", "用栈暂存运算符并按优先级弹出。", 5),
            ("SINGLE", "0/1 背包问题的经典解法是？",
             [("A", "贪心"), ("B", "二分"), ("C", "动态规划"), ("D", "最短路")],
             "C", "按容量维度做 0/1 背包 DP，贪心无法保证最优。", 5),
            ("SINGLE", "并查集最擅长的操作是？",
             [("A", "区间求和"), ("B", "合并集合与查询连通性"), ("C", "单点修改"), ("D", "字符串匹配")],
             "B", "并查集支持近乎常数时间的合并与查询代表元。", 5),
            ("SINGLE", "二叉树的先序遍历顺序是？",
             [("A", "左根右"), ("B", "根左右"), ("C", "左右根"), ("D", "根右左")],
             "B", "先序为根、左子树、右子树。", 5),
            ("SINGLE", "下列排序算法中，平均与最坏时间复杂度均为 O(n log n) 的是？",
             [("A", "快速排序"), ("B", "冒泡排序"), ("C", "归并排序"), ("D", "插入排序")],
             "C", "归并排序的划分始终对半，最坏也是 O(n log n)。", 5),
            ("MULTIPLE", "下列属于稳定排序的有？",
             [("A", "冒泡排序"), ("B", "归并排序"), ("C", "插入排序"), ("D", "堆排序")],
             "ABC", "堆排序在交换时可能打乱相等元素的相对次序，不稳定。", 5),
            ("MULTIPLE", "下列属于线性数据结构的有？",
             [("A", "数组"), ("B", "栈"), ("C", "队列"), ("D", "二叉树")],
             "ABC", "二叉树是非线性的层次结构。", 5),
            ("MULTIPLE", "关于无向图，下列说法正确的有？",
             [("A", "邻接矩阵是对称的"), ("B", "所有顶点度数之和等于边数的两倍"),
              ("C", "一定是连通的"), ("D", "可以用邻接表存储")],
             "ABD", "无向图不要求连通，孤立顶点也是合法的无向图。", 5),
            ("JUDGE", "堆排序是稳定排序。", None,
             "F", "堆排序在建堆与调整过程中会打乱相等元素的顺序。", 5),
            ("JUDGE", "递归函数必须有终止条件，否则可能栈溢出。", None,
             "T", "缺少终止条件会不断压栈，最终栈空间耗尽。", 5),
            ("JUDGE", "在无权图上，BFS 可以求出单源最短路。", None,
             "T", "BFS 按层扩展，首次到达某点的层数即为最短边数。", 5),
        ],
    },
    {
        "id": 1003,
        "title": "CSP-J 2025 复赛模拟（编程）",
        "category": "CSP_J",
        "level": "复赛",
        "duration": 180,
        "published": 1,
        "description": "复赛形式：纯编程两题，各 100 分，按提交会话内的最好成绩计分。",
        "problems": [4000, 4002],
        "questions": [],
    },
    {
        "id": 1004,
        "title": "NOIP 2025 提高组模拟（草稿）",
        "category": "NOIP",
        "level": "提高组",
        "duration": 240,
        "published": 0,
        "description": "尚未发布的提高组模拟赛，用于验证草稿状态不进入学生端列表。",
        "problems": [4003, 4006, 4008],
        "questions": [],
    },
    {
        "id": 1006,
        "title": "CSP-S 2025 复赛模拟（编程）",
        "category": "CSP_S",
        "level": "复赛",
        "duration": 240,
        "published": 1,
        "description": "提高组复赛形式：三道编程题各 100 分，覆盖背包、进制与连通块。",
        "problems": [4003, 4005, 4007],
        "questions": [],
    },
    {
        "id": 1005,
        "title": "期中综合模拟（单选 + 编程）",
        "category": "MOCK",
        "level": "期中",
        "duration": 120,
        "published": 1,
        "description": "客观题与编程题混合卷，验证两类题型同时计分。",
        "problems": [4004],
        "questions": [
            ("SINGLE", "下列哪个数不是素数？",
             [("A", "2"), ("B", "17"), ("C", "91"), ("D", "97")],
             "C", "91 = 7 × 13，是合数。", 5),
            ("SINGLE", "gcd(48, 18) 的值是？",
             [("A", "3"), ("B", "6"), ("C", "9"), ("D", "12")],
             "B", "48 与 18 的最大公约数为 6。", 5),
            ("SINGLE", "斐波那契数列 F(1)=F(2)=1，则 F(10) 的值是？",
             [("A", "34"), ("B", "55"), ("C", "89"), ("D", "144")],
             "B", "数列为 1,1,2,3,5,8,13,21,34,55。", 5),
            ("SINGLE", "2 的 10 次方等于？",
             [("A", "512"), ("B", "1024"), ("C", "2048"), ("D", "100")],
             "B", "2^10 = 1024。", 5),
            ("SINGLE", "5 个结点的完全二叉树，叶子结点个数是？",
             [("A", "2"), ("B", "3"), ("C", "4"), ("D", "5")],
             "B", "结点 1 的子结点为 2、3，结点 2 的子结点为 4、5，叶子是 3、4、5。", 5),
            ("SINGLE", "在无负权边的图上求单源最短路，适合使用？",
             [("A", "Kruskal 算法"), ("B", "Dijkstra 算法"), ("C", "拓扑排序"), ("D", "并查集")],
             "B", "Kruskal 求最小生成树，拓扑排序处理依赖，均不用于最短路。", 5),
            ("JUDGE", "栈可以用数组实现。", None,
             "T", "用数组加一个栈顶指针即可实现顺序栈。", 5),
            ("JUDGE", "队列遵循先进先出原则。", None,
             "T", "这正是队列与栈的核心区别。", 5),
            ("JUDGE", "动态规划一定比递归快。", None,
             "F", "DP 通过消除重复子问题提速，但简单问题用递归可能更快。", 5),
            ("JUDGE", "图的 BFS 可以用来求无权图的最短路。", None,
             "T", "无权图中每条边权为 1，BFS 首次到达即为最短。", 5),
        ],
    },
]

# 作答会话：(attempt_id, paper_id, user_id, status, 距今天数, 开始小时, 已答数, 正确数)
EXAM_ATTEMPTS = [
    (1001, 1001, 1001, "SUBMITTED", 10, 19, 20, 16),
    (1002, 1001, 1004, "SUBMITTED", 8, 20, 20, 18),
    (1003, 1001, 1007, "TIMEOUT", 6, 21, 14, 12),
    (1004, 1001, 1012, "RUNNING", 0, 23, 5, 3),
    (1005, 1002, 1002, "SUBMITTED", 5, 19, 20, 15),
    (1006, 1002, 1009, "SUBMITTED", 3, 20, 18, 11),
    (1007, 1003, 1003, "SUBMITTED", 12, 19, 0, 0),
    (1008, 1003, 1008, "SUBMITTED", 11, 14, 0, 0),
    (1009, 1005, 1005, "SUBMITTED", 4, 19, 10, 7),
    (1010, 1001, 1015, "SUBMITTED", 2, 19, 20, 9),
]

# 编程题的作答计划：attempt_id -> [(problem_id, submission_status)]
EXAM_CODING_PLANS = {
    1007: [(4000, 1), (4002, 2)],
    1008: [(4000, 1), (4002, 1)],
    1009: [(4004, 1)],
}


def wrong_answer(question_type: str, answer: str) -> str:
    """构造一个确定错误的作答，用于生成客观题作答记录。"""
    if question_type == "JUDGE":
        return "F" if answer == "T" else "T"
    keys = list(answer)
    if question_type == "MULTIPLE":
        return "".join(keys[:-1]) if len(keys) > 1 else "AB"
    for key in "ABCD":
        if key not in keys:
            return key
    return "A"


def build_exam_papers():
    """5 套训练试卷：客观题卷、纯编程卷与混合卷各有覆盖。"""
    paper_rows, question_rows, problem_rows = [], [], []
    question_id, pp_id = 1001, 1001

    for paper in EXAM_PAPERS:
        objective_total = sum(q[5] for q in paper["questions"])
        coding_total = 100 * len(paper["problems"])
        paper_rows.append([
            str(paper["id"]), q(paper["title"]), q(paper["description"]),
            q(paper["category"]), q(paper["level"]), str(paper["duration"]),
            str(objective_total + coding_total),
            str(len(paper["questions"]) + len(paper["problems"])),
            str(paper["published"]), "2",
            dt(NOW - timedelta(days=40)),
        ])
        for order, (qtype, statement, options, answer, explanation, score) in enumerate(
            paper["questions"], start=1
        ):
            options_json = (
                json.dumps([{"key": k, "text": t} for k, t in options], ensure_ascii=False)
                if options else None
            )
            question_rows.append([
                str(question_id), str(paper["id"]), q(qtype), q(statement),
                q(options_json) if options_json else "NULL",
                q(answer), q(explanation), str(score), str(order),
            ])
            question_id += 1
        for order, problem_id in enumerate(paper["problems"], start=1):
            problem_rows.append([
                str(pp_id), str(paper["id"]), str(problem_id), str(order), "100",
            ])
            pp_id += 1

    lines = [
        "-- 9) 训练试卷：5 套完整试卷（含客观题与编程题）",
        "--    历史占位卷 2/3 规范为 10 分小测并解除编程题挂载，避免总分口径矛盾",
        "DELETE FROM `exam_paper_problem` WHERE paper_id IN (2, 3);",
        "DELETE FROM `exam_question` WHERE paper_id BETWEEN 1001 AND 1099;",
        "DELETE FROM `exam_paper_problem` WHERE paper_id BETWEEN 1001 AND 1099;",
        "UPDATE `exam_paper` SET title = 'GESP 2级 课堂小测（示例）', level = '2级', total_score = 10,"
        " problem_count = 2 WHERE id = 2;",
        "UPDATE `exam_paper` SET title = 'GESP 2级 课堂小测（副本）', level = '2级', total_score = 10,"
        " problem_count = 2 WHERE id = 3;",
        "",
    ]
    lines += insert_block(
        "exam_paper",
        ["id", "title", "description", "category", "level", "duration_minutes",
         "total_score", "problem_count", "is_published", "create_by", "create_time"],
        paper_rows,
        update_columns=["title", "description", "category", "level", "duration_minutes",
                        "total_score", "problem_count", "is_published"],
    )
    lines.append("")
    lines += insert_block(
        "exam_question",
        ["id", "paper_id", "question_type", "statement", "options", "answer",
         "explanation", "score", "question_order"],
        question_rows,
        update_columns=["paper_id", "question_type", "statement", "options", "answer",
                        "explanation", "score", "question_order"],
    )
    lines.append("")
    lines += insert_block(
        "exam_paper_problem",
        ["id", "paper_id", "problem_id", "problem_order", "score"],
        problem_rows,
        update_columns=["paper_id", "problem_id", "problem_order", "score"],
    )
    lines.append("")
    return lines


def build_exam_attempts():
    """作答会话：客观题记录与编程题提交计划，得分按服务端计分口径推导。"""
    papers = {p["id"]: p for p in EXAM_PAPERS}
    attempt_rows, answer_rows, coding_plans = [], [], []
    answer_id = 2001
    question_ids = {}
    qid = 1001
    for paper in EXAM_PAPERS:
        for _ in paper["questions"]:
            question_ids.setdefault(paper["id"], []).append(qid)
            qid += 1

    for attempt_id, paper_id, user_id, status, days, hour, answered, correct in EXAM_ATTEMPTS:
        paper = papers[paper_id]
        duration = paper["duration"]
        if status == "RUNNING":
            start = NOW - timedelta(minutes=25)
        else:
            start = at_hour(NOW - timedelta(days=days), hour, 0)
        end = start + timedelta(minutes=duration)
        submit = None if status == "RUNNING" else start + timedelta(minutes=duration - 4)

        objective = 0
        for idx, (qtype, _stmt, _opts, answer, _expl, score) in enumerate(
            paper["questions"][:answered]
        ):
            is_correct = idx < correct
            value = answer if is_correct else wrong_answer(qtype, answer)
            gained = score if is_correct else 0
            objective += gained
            answer_rows.append([
                str(answer_id), str(paper_id), str(attempt_id),
                str(question_ids[paper_id][idx]), str(user_id),
                q(value), "1" if is_correct else "0", str(gained),
                dt(start + timedelta(minutes=3 + idx)),
            ])
            answer_id += 1

        coding = 0
        for problem_id, sub_status in EXAM_CODING_PLANS.get(attempt_id, []):
            coding += 100 if sub_status == 1 else 0
            coding_plans.append({
                "user_id": user_id,
                "problem_id": problem_id,
                "status": sub_status,
                "create_time": start + timedelta(minutes=duration // 2),
                "attempt": 1,
                "contest_id": None,
            })

        attempt_rows.append([
            str(attempt_id), str(paper_id), str(user_id), q(status),
            dt(start), dt(end), dt(submit) if submit else "NULL",
            str(objective), str(coding), str(objective + coding),
            dt(start), dt(submit or start + timedelta(minutes=20)),
        ])

    lines = [
        "-- 10) 训练试卷作答：清理本脚本旧会话后重建，覆盖 RUNNING/SUBMITTED/TIMEOUT",
        "DELETE FROM `exam_answer` WHERE attempt_id BETWEEN 1001 AND 1099;",
        "DELETE FROM `exam_attempt` WHERE id BETWEEN 1001 AND 1099;",
        "",
    ]
    lines += insert_block(
        "exam_attempt",
        ["id", "paper_id", "user_id", "status", "start_time", "end_time",
         "submit_time", "objective_score", "coding_score", "total_score",
         "create_time", "update_time"],
        attempt_rows,
        update_columns=["paper_id", "user_id", "status", "start_time", "end_time",
                        "submit_time", "objective_score", "coding_score",
                        "total_score"],
    )
    lines.append("")
    lines += insert_block(
        "exam_answer",
        ["id", "paper_id", "attempt_id", "question_id", "user_id",
         "answer", "is_correct", "score", "create_time"],
        answer_rows,
        update_columns=["paper_id", "attempt_id", "question_id", "user_id",
                        "answer", "is_correct", "score", "create_time"],
    )
    lines.append("")
    return lines, coding_plans

def build_community():
    # 与内置的算法/数据结构分类错开，避免出现"算法""基础"这类半重名分组
    tags = [
        "复盘", "对拍", "调试技巧", "卡题求助", "训练计划", "赛后总结",
        "思路分享", "模板整理", "课程笔记", "学习方法", "机房日常", "提问",
    ]
    rows = [
        [str(1001 + i), q(name), q("学习经验"), "1", "1", dt(NOW - timedelta(days=60))]
        for i, name in enumerate(tags)
    ]
    lines = ["-- 9) 社区标签与帖子-题目关联"]
    lines += insert_block(
        "community_tag",
        ["id", "name", "group_name", "enabled", "created_by", "create_time"],
        rows, update_columns=["name", "group_name", "enabled"],
    )
    lines.append("")

    relations = [(2, 8), (22, 8), (23, 11), (25, 4)]
    rows = [
        [str(1001 + i), str(post_id), str(problem_id), dt(NOW - timedelta(days=30))]
        for i, (post_id, problem_id) in enumerate(relations)
    ]
    lines += insert_block(
        "post_problem_relation",
        ["id", "post_id", "problem_id", "create_time"],
        rows, update_columns=["post_id", "problem_id"],
    )
    lines.append("")
    return lines


def build_notifications(students):
    """给一部分学生生成站内通知，覆盖全部通知类型。"""
    types = [
        ("LIKE", "有人点赞了你的题解", "POST"),
        ("COMMENT", "你的帖子收到一条评论", "POST"),
        ("REPLY", "有人回复了你的评论", "COMMENT"),
        ("FAVORITE", "有人收藏了你的帖子", "POST"),
        ("FOLLOW_POST", "你关注的作者发布了新内容", "POST"),
        ("MODERATION", "你的内容已被限制可见", "POST"),
    ]
    rows = []
    nid = 1001
    for idx, uid in enumerate(students[:18]):
        for k in range(3):
            kind, title, target_type = types[(idx + k) % len(types)]
            created = NOW - timedelta(days=(idx * 3 + k * 2) % 40, hours=idx % 12)
            rows.append([
                str(nid), str(uid), str(2 if k % 2 else 6), q(kind),
                q(target_type), str(1 if target_type == "POST" else 5),
                q(title), q("点击查看详情"),
                "1" if k == 2 and idx % 4 == 0 else "0",
                dt(created),
                dt(created + timedelta(hours=2)) if k == 2 and idx % 4 == 0 else "NULL",
            ])
            nid += 1
    lines = ["-- 10) 站内通知（覆盖 LIKE/COMMENT/REPLY/FAVORITE/FOLLOW_POST/MODERATION）"]
    lines += insert_block(
        "user_notification",
        ["id", "user_id", "actor_id", "type", "target_type", "target_id",
         "title", "content", "is_read", "create_time", "read_time"],
        rows,
        update_columns=["type", "target_type", "target_id", "title", "content",
                        "is_read", "create_time"],
    )
    lines.append("")
    return lines


def build_badges():
    """按既有徽章规则给达标学生发徽章。

    - 条件判定直接读 submission，不依赖 user.accepted_count（对账在后面才执行）；
    - id 由 user_id 派生，重复执行命中同一行，不会因为行号变化而重复发放。
    """
    lines = [
        "-- 11) 徽章：按 badge 表条件发放（id 由 user_id 派生，重复执行不重复发放）",
        """INSERT INTO `user_badge` (id, user_id, badge_id, obtain_time, is_wearing)
SELECT 100000 + s.user_id, s.user_id, 1, NOW(), 1
FROM (
    SELECT user_id, COUNT(DISTINCT CASE WHEN status = 1 THEN problem_id END) AS ac
    FROM `submission`
    WHERE user_id BETWEEN 1001 AND 1050
    GROUP BY user_id
) s
WHERE s.ac >= 10
ON DUPLICATE KEY UPDATE is_wearing = VALUES(is_wearing);""",
        """INSERT INTO `user_badge` (id, user_id, badge_id, obtain_time, is_wearing)
SELECT 200000 + s.user_id, s.user_id, 2, NOW(), 0
FROM (
    SELECT user_id, COUNT(DISTINCT CASE WHEN status = 1 THEN problem_id END) AS ac
    FROM `submission`
    WHERE user_id BETWEEN 1001 AND 1050
    GROUP BY user_id
) s
WHERE s.ac >= 50
ON DUPLICATE KEY UPDATE is_wearing = VALUES(is_wearing);""",
        """INSERT INTO `user_badge` (id, user_id, badge_id, obtain_time, is_wearing)
SELECT 300000 + t.user_id, t.user_id, 3, NOW(), 1
FROM (
    SELECT DISTINCT cu.user_id
    FROM `contest_user` cu
    JOIN `contest` c ON c.id = cu.contest_id
    WHERE cu.user_id BETWEEN 1001 AND 1050
      AND c.end_time < NOW()
      AND cu.status IN ('AUTO_APPROVED', 'APPROVED')
) t
ON DUPLICATE KEY UPDATE is_wearing = VALUES(is_wearing);""",
        "",
    ]
    return lines


def build_review_and_tags():
    """错题本复用应用自身的收录规则；标签能力按提交分布派生（仅限新增学生）。"""
    lines = [
        "-- 12) 错题本：先按应用规则回收已通过的题，再按同一规则收录未通过的题",
        """UPDATE `user_problem_review` r
JOIN (
    SELECT DISTINCT user_id, problem_id FROM `submission` WHERE status = 1
) a ON a.user_id = r.user_id AND a.problem_id = r.problem_id
SET r.status = 'MASTERED', r.last_reviewed_at = NOW()
WHERE r.status = 'REVIEW';""",
        """INSERT INTO `user_problem_review` (user_id, problem_id, status, reason, first_flagged_at)
SELECT user_id, problem_id, 'REVIEW',
       CASE WHEN SUM(status = 2) >= 2 THEN 'REPEATED_WA' ELSE 'UNSOLVED_7_DAYS' END,
       MIN(create_time)
FROM `submission`
WHERE status <> 0
GROUP BY user_id, problem_id
HAVING SUM(status = 1) = 0
   AND (SUM(status = 2) >= 2
        OR MIN(create_time) <= DATE_SUB(NOW(), INTERVAL 7 DAY))
ON DUPLICATE KEY UPDATE reason = VALUES(reason);""",
        "",
        "-- 13) 标签能力（user_tag_rating）：由提交分布派生，只覆盖新增的 50 名学生",
        """INSERT INTO `user_tag_rating` (user_id, tag_id, rating, problem_count)
SELECT s.user_id, pt.tag_id,
       LEAST(1800, 780 + 40 * COUNT(DISTINCT CASE WHEN s.status = 1 THEN s.problem_id END)),
       COUNT(DISTINCT s.problem_id)
FROM `submission` s
JOIN `problem_tag` pt ON pt.problem_id = s.problem_id
WHERE s.user_id BETWEEN 1001 AND 1050
GROUP BY s.user_id, pt.tag_id
ON DUPLICATE KEY UPDATE rating = VALUES(rating), problem_count = VALUES(problem_count);""",
        "",
    ]
    return lines


def build_class_content(pool):
    """班级题单（type=3）、班级作业（type=4）与课件，让班级页与作业状态页有内容。"""
    homework_1 = [p for p in (1, 2, 4, 8) if p in pool]
    homework_2 = [p for p in (10, 11, 15, 5) if p in pool]
    class_list_1 = [p for p in (1, 2, 3, 4, 5, 8) if p in pool]
    class_list_2 = [p for p in (10, 11, 15, 9, 13) if p in pool]

    lists = [
        # id, title, creator, desc, type, class_id, start, end
        (1001, "第 8 周作业：线性结构与二分", 2,
         "配套第 8 周课程，覆盖数组、栈、二分与基础 DP。", 4, 1,
         NOW - timedelta(days=21), NOW + timedelta(days=7)),
        (1002, "第 8 周作业：图论与 DP 综合", 2,
         "进阶班第 8 周作业，侧重最短路、生成树与 DP 建模。", 4, 2,
         NOW - timedelta(days=21), NOW + timedelta(days=7)),
        (1003, "基础班随堂练习：数组与字符串", 2,
         "课堂随堂练习，不计入作业成绩。", 3, 1, None, None),
        (1004, "进阶班专题：图论精选", 2,
         "课堂补充题单，供课后自主练习。", 3, 2, None, None),
    ]

    list_rows = []
    for list_id, title, creator, desc, ltype, class_id, start, end in lists:
        list_rows.append([
            str(list_id), q(title), str(creator), q(desc), str(ltype),
            str(class_id), "1", dt(start) if start else "NULL",
            dt(end) if end else "NULL", "0", "0", dt(NOW - timedelta(days=18)),
        ])

    item_rows = []
    next_item = 1001
    for list_id, problems in ((1001, homework_1), (1002, homework_2),
                              (1003, class_list_1), (1004, class_list_2)):
        for order, problem_id in enumerate(problems, start=1):
            item_rows.append([str(next_item), str(list_id), str(problem_id), str(order)])
            next_item += 1

    lines = ["-- 15) 班级题单与班级作业（type=3 题单 / type=4 作业）"]
    lines += insert_block(
        "problem_list",
        ["id", "title", "creator_id", "description", "type", "class_id",
         "privacy", "start_time", "end_time", "problem_count", "favorite_count",
         "create_time"],
        list_rows,
        update_columns=["title", "description", "type", "class_id", "privacy",
                        "start_time", "end_time"],
    )
    lines.append("")
    lines += insert_block(
        "problem_list_item",
        ["id", "list_id", "problem_id", "sort"],
        item_rows, update_columns=["list_id", "problem_id", "sort"],
    )
    lines.append("")

    courseware = [
        (1001, 2, "第四讲：链表与递归", "/slides/linked-list", 0,
         "链表的基本操作、递归思维与常见陷阱", 1),
        (1002, 2, "第五讲：树的遍历", "/slides/tree-traversal", 0,
         "前中后序遍历、层序遍历与递归改写", 1),
        (1003, 2, "第六讲：图论入门", "/slides/graph-intro", 0,
         "图的表示、DFS/BFS 与连通性判断", 1),
        (1004, 1, "进制转换与位运算", "/slides/base-and-bits", 0,
         "短除法、补码与常用位运算技巧", 1),
    ]
    rows = [
        [str(cid), str(tid), q(title), q(url), str(ctype), "NULL",
         q("本讲配套讲义与例题，课后请完成对应题单。"), q(desc), str(priv),
         dt(NOW - timedelta(days=30))]
        for cid, tid, title, url, ctype, desc, priv in courseware
    ]
    lines += insert_block(
        "courseware",
        ["id", "teacher_id", "title", "url", "type", "problem_list_id",
         "content", "description", "privacy", "create_time"],
        rows,
        update_columns=["title", "url", "type", "description", "privacy"],
    )
    lines.append("")

    links = [
        (1001, 1, 1001, 4, 1), (1002, 1, 1002, 5, 1),
        (1003, 2, 1003, 2, 1), (1004, 2, 1001, 3, 1),
    ]
    rows = [
        [str(lid), str(class_id), str(cw_id), str(sort), str(visible)]
        for lid, class_id, cw_id, sort, visible in links
    ]
    lines += insert_block(
        "class_courseware",
        ["id", "class_id", "courseware_id", "sort", "is_visible"],
        rows, update_columns=["class_id", "courseware_id", "sort", "is_visible"],
    )
    lines.append("")
    return lines


def build_settlement_and_replay():
    """已结束比赛的结算记录与结算回放审计（含成功与失败各一条）。"""
    lines = [
        "-- 11) 比赛结算与结算回放：让结算回放页有可预览、可恢复的记录",
        """INSERT INTO `contest_settlement`
    (contest_id, algorithm_version, rating_algorithm_version, settlement_no,
     finalized_at, create_time, update_time)
SELECT c.id, 'v1', 'elo-v1', 1, c.rank_finalized_at, c.rank_finalized_at, c.rank_finalized_at
FROM `contest` c
WHERE c.id IN (1003, 1004)
  AND c.rank_finalized_at IS NOT NULL
ON DUPLICATE KEY UPDATE algorithm_version = VALUES(algorithm_version),
    rating_algorithm_version = VALUES(rating_algorithm_version),
    finalized_at = VALUES(finalized_at);""",
        "",
        """INSERT INTO `contest_settlement_replay`
    (id, from_contest_id, operator_id, reason, status, total_contests,
     affected_users, backup_json, started_at, finished_at, create_time)
VALUES
(1001, 1003, 1, '赛题数据修正后重放后续比赛结算', 'COMPLETED', 2, 34,
 '{"contests":[1003,1004],"note":"测试环境演示用的结算备份摘要"}',
 DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 5 DAY) + INTERVAL 40 SECOND,
 DATE_SUB(NOW(), INTERVAL 5 DAY)),
(1002, 1004, 1, 'Rating 算法回滚演练（人工终止）', 'FAILED', 1, 0, NULL,
 DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY) + INTERVAL 8 SECOND,
 DATE_SUB(NOW(), INTERVAL 2 DAY))
ON DUPLICATE KEY UPDATE reason = VALUES(reason), status = VALUES(status),
    total_contests = VALUES(total_contests), affected_users = VALUES(affected_users),
    backup_json = VALUES(backup_json), started_at = VALUES(started_at),
    finished_at = VALUES(finished_at);""",
        "",
        "UPDATE `contest_settlement_replay`"
        " SET error_message = '结算守卫检测到未完成提交，已终止' WHERE id = 1002;",
        "",
    ]
    return lines


def build_rejudge():
    """重判任务：一次全成功、一次部分失败。"""
    return [
        "-- 12) 重判任务：COMPLETED 与 PARTIAL 各一条，覆盖成功与失败明细",
        """INSERT INTO `rejudge_operation`
    (id, operator_id, operator_name, reason, scope_type, scope_json, status,
     total_items, succeeded_items, failed_items, created_at, started_at,
     finished_at, error_message)
VALUES
(1001, 1, '系统管理员', '测试用例更新后重判该题全部提交', 'PROBLEM',
 '{"problemId":4000}', 'COMPLETED', 6, 6, 0,
 DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY),
 DATE_SUB(NOW(), INTERVAL 6 DAY) + INTERVAL 95 SECOND, NULL),
(1002, 1, '系统管理员', '判题机升级后的抽样复核', 'ENDED_CONTEST',
 '{"contestId":1004}', 'PARTIAL', 4, 2, 2,
 DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY),
 DATE_SUB(NOW(), INTERVAL 3 DAY) + INTERVAL 60 SECOND,
 '2 条提交因用例文件缺失未能重判')
ON DUPLICATE KEY UPDATE reason = VALUES(reason), status = VALUES(status),
    total_items = VALUES(total_items), succeeded_items = VALUES(succeeded_items),
    failed_items = VALUES(failed_items), started_at = VALUES(started_at),
    finished_at = VALUES(finished_at), error_message = VALUES(error_message);""",
        "",
        """INSERT INTO `rejudge_item`
    (id, operation_id, submission_id, problem_id, user_id, contest_id,
     vp_attempt_id, testcase_digest, old_result_json, new_result_json, status,
     created_at, finished_at, error_message)
SELECT 1001 + ROW_NUMBER() OVER (ORDER BY s.id), 1001, s.id, s.problem_id,
       s.user_id, s.contest_id, s.vp_attempt_id,
       SHA2(CONCAT(s.problem_id, '-', s.id), 256),
       JSON_OBJECT('status', s.status, 'score', s.score),
       JSON_OBJECT('status', 1, 'score', 100), 'SUCCEEDED',
       DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY) + INTERVAL 3 SECOND,
       NULL
FROM `submission` s
WHERE s.problem_id = 4000
ORDER BY s.id
LIMIT 6
ON DUPLICATE KEY UPDATE status = VALUES(status), new_result_json = VALUES(new_result_json);""",
        "",
        """INSERT INTO `rejudge_item`
    (id, operation_id, submission_id, problem_id, user_id, contest_id,
     vp_attempt_id, testcase_digest, old_result_json, new_result_json, status,
     created_at, finished_at, error_message)
SELECT 1201 + ROW_NUMBER() OVER (ORDER BY s.id), 1002, s.id, s.problem_id,
       s.user_id, s.contest_id, s.vp_attempt_id,
       SHA2(CONCAT(s.problem_id, '-', s.id), 256),
       JSON_OBJECT('status', s.status, 'score', s.score),
       JSON_OBJECT('status', 1, 'score', 100), 'SUCCEEDED',
       DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY) + INTERVAL 2 SECOND,
       NULL
FROM `submission` s
WHERE s.contest_id = 1004
ORDER BY s.id DESC
LIMIT 2
ON DUPLICATE KEY UPDATE status = VALUES(status), new_result_json = VALUES(new_result_json);""",
        "",
        """INSERT INTO `rejudge_item`
    (id, operation_id, submission_id, problem_id, user_id, contest_id,
     vp_attempt_id, testcase_digest, old_result_json, new_result_json, status,
     created_at, finished_at, error_message)
SELECT 1101 + ROW_NUMBER() OVER (ORDER BY s.id), 1002, s.id, s.problem_id,
       s.user_id, s.contest_id, s.vp_attempt_id,
       SHA2(CONCAT(s.problem_id, '-', s.id), 256),
       JSON_OBJECT('status', s.status, 'score', s.score),
       NULL, 'FAILED',
       DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY) + INTERVAL 2 SECOND,
       '用例文件缺失：problem_case 记录不存在'
FROM `submission` s
WHERE s.contest_id = 1004
ORDER BY s.id
LIMIT 2
ON DUPLICATE KEY UPDATE status = VALUES(status), error_message = VALUES(error_message);""",
        "",
    ]


VP1_START = NOW - timedelta(days=7)
VP1_END = VP1_START + timedelta(minutes=120)
VP2_START = NOW - timedelta(minutes=40)
VP2_END = VP2_START + timedelta(minutes=180)


def build_vp():
    """虚拟赛会话：错过正式赛后按原时长补打，覆盖进行中与已完成。"""
    rows = [
        ["1001", "1004", "1011", q("FINISHED"),
         dt(VP1_START), dt(VP1_END), dt(VP1_START + timedelta(minutes=110)),
         "300", "3", "145", "12", "20",
         dt(VP1_START), dt(VP1_START + timedelta(minutes=110))],
        ["1002", "1003", "1016", q("RUNNING"),
         dt(VP2_START), dt(VP2_END), "NULL",
         "0", "0", "0", "NULL", "NULL",
         dt(VP2_START), dt(VP2_START)],
    ]
    lines = ["-- 13) 虚拟赛（VP）：补打会话"]
    lines += insert_block(
        "vp_attempt",
        ["id", "contest_id", "user_id", "status", "start_time", "end_time",
         "submit_time", "score", "solved_count", "penalty_time", "rank_num",
         "total_participants", "create_time", "update_time"],
        rows,
        update_columns=["contest_id", "user_id", "status", "start_time", "end_time",
                        "submit_time", "score", "solved_count", "penalty_time",
                        "rank_num", "total_participants"],
    )
    lines.append("")
    return lines


def build_vp_submissions(contest_problems):
    """VP 会话内提交：3 题通过、1 题未过，与 vp_attempt 的得分口径一致。"""
    statuses = [1, 1, 1, 2]
    return [
        {
            "user_id": 1011,
            "problem_id": problem_id,
            "status": statuses[idx],
            "create_time": VP1_START + timedelta(minutes=12 + idx * 18),
            "attempt": 1,
            "contest_id": 1004,
            "vp_attempt_id": 1001,
        }
        for idx, problem_id in enumerate(contest_problems[:4])
    ]


def build_plagiarism_pairs(start_sub_id, start_case_id, pool, case_map):
    """构造 3 对代码完全相同的提交，并生成对应查重报告。"""
    pairs = [
        (1002, 1003, 1, 0.97, "PENDING"),
        (1005, 1006, 2, 0.91, "REVIEWED"),
        (1010, 1011, 4, 0.88, "IGNORED"),
    ]
    sub_rows, code_rows, result_rows, case_rows, report_rows = [], [], [], [], []
    sub_id, case_id, report_id = start_sub_id, start_case_id, 1001

    for user_a, user_b, problem_id, similarity, status in pairs:
        if problem_id not in pool:
            continue
        shared_code = (
            "#include <bits/stdc++.h>\n"
            "using namespace std;\n"
            f"// problem {problem_id} / 同源代码\n"
            "int main() { return 0; }"
        )
        ids = []
        for user_id in (user_a, user_b):
            run_time = 20 + sub_id % 17
            sub_rows.append([
                str(sub_id), str(problem_id), str(user_id), q("cpp"), "100", "1",
                str(run_time), "20480", dt(NOW - timedelta(days=13)), q("FINISHED"),
                "NULL", "NULL", "NULL",
            ])
            code_rows.append([str(sub_id), q(shared_code)])
            result_rows.append([
                str(sub_id), "1", "100", str(run_time), "20480",
                dt(NOW - timedelta(days=13) + timedelta(seconds=2)),
            ])
            for case_pk, case_order in case_map.get(problem_id, [])[:5]:
                case_rows.append([
                    str(case_id), str(sub_id), str(case_pk), str(case_order), "1",
                    str(run_time), "20480", "NULL",
                ])
                case_id += 1
            ids.append(sub_id)
            sub_id += 1
        report_rows.append([
            str(report_id), "NULL", "NULL", str(problem_id), str(ids[0]), str(ids[1]),
            str(user_a), str(user_b), str(similarity), q("AST"), q(status),
            dt(NOW - timedelta(days=12)),
        ])
        report_id += 1

    lines = ["-- 14) 查重样本：3 对代码完全相同的提交"]
    lines += insert_block(
        "submission",
        ["id", "problem_id", "user_id", "language", "score", "status", "run_time",
         "run_memory", "create_time", "judge_phase", "contest_id", "vp_attempt_id",
         "error_message"],
        sub_rows,
        update_columns=["problem_id", "user_id", "language", "score", "status",
                        "run_time", "run_memory", "create_time", "judge_phase"],
    )
    lines.append("")
    lines += insert_block("submission_code", ["submission_id", "code"], code_rows,
                          update_columns=["code"])
    lines.append("")
    lines += insert_block(
        "submission_result",
        ["submission_id", "status", "score", "run_time", "run_memory", "judged_at"],
        result_rows, update_columns=["status", "score", "run_time", "run_memory"],
    )
    lines.append("")
    lines.append(
        "DELETE FROM `submission_case_result` WHERE submission_id BETWEEN "
        f"{start_sub_id} AND {sub_id - 1};"
    )
    lines += insert_block(
        "submission_case_result",
        ["id", "submission_id", "problem_case_id", "case_order", "status",
         "run_time", "run_memory", "error_message"],
        case_rows,
        update_columns=["submission_id", "problem_case_id", "case_order", "status"],
    )
    lines.append("")

    report_lines = ["-- 14b) 查重报告（PENDING / REVIEWED / IGNORED 三种状态）"]
    report_lines += insert_block(
        "plagiarism_report",
        ["id", "class_id", "contest_id", "problem_id", "submission_a_id",
         "submission_b_id", "user_a_id", "user_b_id", "similarity", "method",
         "status", "created_at"],
        report_rows,
        update_columns=["problem_id", "submission_a_id", "submission_b_id",
                        "user_a_id", "user_b_id", "similarity", "status"],
    )
    report_lines.append("")
    return lines, report_lines, sub_id - 1, case_id - 1


def build_content_reports():
    """内容举报：覆盖 PENDING / RESOLVED / DISMISSED 三种状态。"""
    return [
        "-- 15) 内容举报",
        """INSERT INTO `content_report`
    (id, reporter_id, post_id, reason, detail, status, handler_id, handle_note,
     handled_at, create_time)
VALUES
(1001, 1003, 24, '广告推广', '正文末尾附带外部引流链接', 'PENDING', NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 30 HOUR)),
(1002, 1008, 21, '不友善内容', '回复中使用了带有攻击性的表述', 'RESOLVED', 1,
 '已隐藏该内容并提醒作者', DATE_SUB(NOW(), INTERVAL 2 DAY),
 DATE_SUB(NOW(), INTERVAL 3 DAY)),
(1003, 1012, 23, '内容重复', '与已有题解高度重复', 'DISMISSED', 1,
 '核实为独立撰写，保留原帖', DATE_SUB(NOW(), INTERVAL 5 DAY),
 DATE_SUB(NOW(), INTERVAL 6 DAY)),
(1004, 1015, 20, '信息不实', '课程费用描述与实际不符', 'PENDING', NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 10 HOUR))
ON DUPLICATE KEY UPDATE status = VALUES(status), handler_id = VALUES(handler_id),
    handle_note = VALUES(handle_note), handled_at = VALUES(handled_at),
    detail = VALUES(detail);""",
        "",
    ]

def build_reconcile():
    """把冗余计数按源表重算，避免页面数字自相矛盾。"""
    lines = [
        "-- 14) 对账：冗余计数按源表重算（可重复执行，结果只取决于提交/成员/条目）",
        """UPDATE `user` u
LEFT JOIN (
    SELECT user_id,
           COUNT(*) AS total,
           COUNT(DISTINCT CASE WHEN status = 1 THEN problem_id END) AS accepted
    FROM `submission`
    GROUP BY user_id
) s ON s.user_id = u.id
SET u.submit_count = COALESCE(s.total, 0),
    u.accepted_count = COALESCE(s.accepted, 0);""",
        "",
        """UPDATE `problem` p
LEFT JOIN (
    SELECT problem_id,
           COUNT(*) AS total,
           COUNT(CASE WHEN status = 1 THEN 1 END) AS accepted
    FROM `submission`
    GROUP BY problem_id
) s ON s.problem_id = p.id
SET p.total_count = COALESCE(s.total, 0),
    p.pass_count = COALESCE(s.accepted, 0),
    p.test_case_count = (
        SELECT COUNT(*) FROM `problem_case` c
        WHERE c.problem_id = p.id AND c.case_type = 0
    );""",
        "",
        """UPDATE `class` c
LEFT JOIN (
    SELECT class_id, COUNT(*) AS members FROM `class_student` GROUP BY class_id
) s ON s.class_id = c.id
SET c.student_count = COALESCE(s.members, 0);""",
        "",
        """UPDATE `problem_list` pl
LEFT JOIN (
    SELECT list_id, COUNT(*) AS items FROM `problem_list_item` GROUP BY list_id
) s ON s.list_id = pl.id
SET pl.problem_count = COALESCE(s.items, 0);""",
        "",
    ]
    return lines


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="补充测试环境演示数据")
    parser.add_argument("--generate-only", action="store_true",
                        help="只生成 SQL 文件，不写库")
    args = parser.parse_args()

    print("正在读取题库……", file=sys.stderr)
    pool = fetch_problem_pool()
    print(f"可用题目 {len(pool)} 道", file=sys.stderr)

    case_map_sql = """
SELECT c.problem_id, c.id, c.case_order
FROM `problem_case` c
WHERE c.case_type = 0
ORDER BY c.problem_id, c.case_order;
"""
    case_map = {}
    for problem_id, case_id, case_order in run_sql(case_map_sql, capture=True):
        case_map.setdefault(int(problem_id), []).append((int(case_id), int(case_order)))

    # 尚未入库的用例也要出现在提交计划里，否则首轮生成与后续轮次结果不一致
    for new_id, problem_id, _case_id, order in restore_case_rows():
        existing = case_map.setdefault(problem_id, [])
        if not existing:
            existing.append((new_id, order))

    seq_cases = case_map.setdefault(SEQ_PROBLEM_ID, [])
    if not seq_cases:
        for order, case_id in enumerate(SEQ_CASE_IDS, start=1):
            seq_cases.append((case_id, order))

    write_seq_testcase_files()

    plans = plan_submissions(pool)
    lines = ["-- 测试环境演示数据（由 scripts/seed-testenv.py 生成，请勿手工编辑）",
             "-- 用法：python3 scripts/seed-testenv.py", "",
             "SET NAMES utf8mb4;", ""]
    lines += build_part1()

    student_lines, students = build_students()
    sub_lines, last_sub_id, last_case_id = build_submissions(
        plans, case_map, header="-- 6) 提交记录（含源码、判题结果与逐点结果）"
    )

    contest_lines, contests = build_contests(pool)
    contest_plans = build_contest_submissions(contests)
    contest_sub_lines = []
    if contest_plans:
        contest_sub_lines, last_sub_id, last_case_id = build_submissions(
            contest_plans, case_map,
            start_id=last_sub_id + 1,
            start_case_id=last_case_id + 1,
            header="-- 6b) 赛内提交（contest_id 已标注）",
        )

    exam_lines, exam_coding_plans = build_exam_attempts()
    exam_sub_lines = []
    if exam_coding_plans:
        exam_sub_lines, last_sub_id, last_case_id = build_submissions(
            exam_coding_plans, case_map,
            start_id=last_sub_id + 1,
            start_case_id=last_case_id + 1,
            header="-- 10b) 训练试卷编程题提交（落在作答会话时间窗内）",
        )

    vp_plans = build_vp_submissions(contests[3]["problems"])
    vp_sub_lines = []
    if vp_plans:
        vp_sub_lines, last_sub_id, last_case_id = build_submissions(
            vp_plans, case_map,
            start_id=last_sub_id + 1,
            start_case_id=last_case_id + 1,
            header="-- 13b) 虚拟赛提交（带 vp_attempt_id）",
        )

    plag_lines, plag_report_lines, last_sub_id, last_case_id = build_plagiarism_pairs(
        last_sub_id + 1, last_case_id + 1, pool, case_map
    )

    lines += student_lines
    lines += sub_lines
    lines += contest_sub_lines
    lines += exam_sub_lines
    lines += vp_sub_lines
    lines += plag_lines
    lines += build_contest_levels()
    lines += contest_lines
    lines += build_exam_papers()
    lines += exam_lines
    lines += build_settlement_and_replay()
    lines += build_rejudge()
    lines += build_vp()
    lines += plag_report_lines
    lines += build_content_reports()
    lines += build_community()
    lines += build_class_content(pool)
    lines += build_notifications(students)
    lines += build_badges()
    lines += build_review_and_tags()
    lines += build_reconcile()
    lines += ["SET FOREIGN_KEY_CHECKS = 1;", ""]

    sql_text = "\n".join(lines)
    SQL_OUT.parent.mkdir(parents=True, exist_ok=True)
    SQL_OUT.write_text(sql_text, encoding="utf-8")
    print(f"已生成 {SQL_OUT}（{len(sql_text.splitlines())} 行）", file=sys.stderr)

    if args.generate_only:
        return

    print("正在写入数据库……", file=sys.stderr)
    run_sql("SET FOREIGN_KEY_CHECKS = 0;\n" + sql_text)
    print("写入完成。", file=sys.stderr)


if __name__ == "__main__":
    main()