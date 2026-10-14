#!/usr/bin/env python3
"""生成 MuseAlgo-OJ 演示题目（problem 1..15）的判题用例文件。

设计要点
--------
1. 这些用例是为开源演示**原创编写**的，不取自任何第三方题库。
2. 期望输出全部由本文件中的参考实现计算得出，而不是手工填写——
   手工填写的期望输出是演示数据最容易出错的地方。
3. 输出目录与后端读取规则严格对齐：
       {oj.testcase.base-path}/{problemId}/{caseId}/input.txt
       {oj.testcase.base-path}/{problemId}/{caseId}/output.txt
   （见 oj-problem TestCaseStorageService.caseDir/inputFile/outputFile）
4. 同时向 stdout 打印可直接粘进 OJ-backend/db/seed.sql 的 problem_case INSERT 语句。

用法
----
    python3 scripts/gen-demo-testcases.py                # 写文件 + 打印 SQL
    python3 scripts/gen-demo-testcases.py --sql-only     # 只打印 SQL
    python3 scripts/gen-demo-testcases.py --check        # 只校验已有文件与参考实现一致
"""

from __future__ import annotations

import argparse
import heapq
import itertools
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
OUTPUT_ROOT = REPO_ROOT / "OJ-backend" / "db" / "demo-testcases"

MOD = 1_000_000_007


# --------------------------------------------------------------------------
# 参考实现：每个函数返回该题的期望输出（字符串，含结尾换行）
# --------------------------------------------------------------------------

def solve_two_sum(data: str) -> str:
    lines = data.splitlines()
    n = int(lines[0])
    nums = list(map(int, lines[1].split()))
    target = int(lines[2])
    assert len(nums) == n, "用例自检：数组长度与 n 不一致"
    seen: dict[int, int] = {}
    for i, v in enumerate(nums):
        if target - v in seen:
            return f"{seen[target - v]} {i}\n"
        seen[v] = i
    raise AssertionError("用例自检：两数之和必须有解")


def solve_brackets(data: str) -> str:
    s = data.strip()
    pairs = {")": "(", "]": "[", "}": "{"}
    stack: list[str] = []
    for ch in s:
        if ch in "([{":
            stack.append(ch)
        elif ch in pairs:
            if not stack or stack.pop() != pairs[ch]:
                return "NO\n"
    return "YES\n" if not stack else "NO\n"


def solve_lcp(data: str) -> str:
    lines = data.splitlines()
    n = int(lines[0])
    words = lines[1:1 + n]
    assert len(words) == n, "用例自检：字符串个数与 n 不一致"
    prefix = words[0]
    for w in words[1:]:
        while not w.startswith(prefix):
            prefix = prefix[:-1]
            if not prefix:
                return "\n"
    return prefix + "\n"


def solve_stairs(data: str) -> str:
    n = int(data.strip())
    a, b = 1, 1
    for _ in range(n):
        a, b = b, a + b
    return f"{a}\n"


def solve_max_subarray(data: str) -> str:
    lines = data.splitlines()
    n = int(lines[0])
    nums = list(map(int, lines[1].split()))
    assert len(nums) == n, "用例自检：数组长度与 n 不一致"
    best = cur = nums[0]
    for v in nums[1:]:
        cur = max(v, cur + v)
        best = max(best, cur)
    return f"{best}\n"


def solve_reverse_list(data: str) -> str:
    lines = data.splitlines()
    n = int(lines[0])
    values = list(map(int, lines[1].split())) if n else []
    assert len(values) == n, "用例自检：节点个数与 n 不一致"
    return " ".join(map(str, reversed(values))) + "\n"


def solve_tree_depth(data: str) -> str:
    lines = data.splitlines()
    n = int(lines[0])
    children: dict[int, list[int]] = {}
    for line in lines[1:1 + n - 1]:
        parent, child, _side = map(int, line.split())
        children.setdefault(parent, []).append(child)
    depth = 0
    stack = [(1, 1)]
    while stack:
        node, d = stack.pop()
        depth = max(depth, d)
        for c in children.get(node, []):
            stack.append((c, d + 1))
    return f"{depth}\n"


def solve_binary_search(data: str) -> str:
    lines = data.splitlines()
    n, target = map(int, lines[0].split())
    nums = list(map(int, lines[1].split()))
    assert len(nums) == n, "用例自检：数组长度与 n 不一致"
    lo, hi = 0, n - 1
    while lo <= hi:
        mid = lo + (hi - lo) // 2
        if nums[mid] == target:
            return f"{mid}\n"
        if nums[mid] < target:
            lo = mid + 1
        else:
            hi = mid - 1
    return "-1\n"


def solve_coin_change(data: str) -> str:
    lines = data.splitlines()
    n, amount = map(int, lines[0].split())
    coins = list(map(int, lines[1].split()))
    assert len(coins) == n, "用例自检：硬币个数与 n 不一致"
    inf = float("inf")
    dp = [0] + [inf] * amount
    for j in range(1, amount + 1):
        for c in coins:
            if c <= j and dp[j - c] + 1 < dp[j]:
                dp[j] = dp[j - c] + 1
    return "-1\n" if dp[amount] == inf else f"{int(dp[amount])}\n"


def solve_dijkstra(data: str) -> str:
    lines = data.splitlines()
    n, m = map(int, lines[0].split())
    graph: dict[int, list[tuple[int, int]]] = {}
    for line in lines[1:1 + m]:
        u, v, w = map(int, line.split())
        graph.setdefault(u, []).append((v, w))
    dist = {1: 0}
    heap = [(0, 1)]
    while heap:
        d, u = heapq.heappop(heap)
        if d > dist.get(u, float("inf")):
            continue
        for v, w in graph.get(u, []):
            nd = d + w
            if nd < dist.get(v, float("inf")):
                dist[v] = nd
                heapq.heappush(heap, (nd, v))
    return f"{dist[n]}\n" if n in dist else "-1\n"


def solve_mst(data: str) -> str:
    lines = data.splitlines()
    n, m = map(int, lines[0].split())
    edges = [tuple(map(int, line.split())) for line in lines[1:1 + m]]
    edges.sort(key=lambda e: e[2])
    parent = list(range(n + 1))

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    total, used = 0, 0
    for u, v, w in edges:
        ru, rv = find(u), find(v)
        if ru != rv:
            parent[ru] = rv
            total += w
            used += 1
    assert used == n - 1, "用例自检：最小生成树用例的图必须连通"
    return f"{total}\n"


def solve_pow(data: str) -> str:
    x, n = map(int, data.split())
    return f"{pow(x % MOD, n, MOD)}\n"


def solve_permutations(data: str) -> str:
    lines = data.splitlines()
    n = int(lines[0])
    values = list(map(int, lines[1].split()))
    assert len(values) == n, "用例自检：整数个数与 n 不一致"
    out = [" ".join(map(str, p)) for p in itertools.permutations(sorted(values))]
    return "\n".join(out) + "\n"


def solve_kmp(data: str) -> str:
    lines = data.splitlines()
    text, pattern = lines[0], lines[1]
    hits = []
    start = text.find(pattern)
    while start != -1:
        hits.append(start)
        start = text.find(pattern, start + 1)
    return (" ".join(map(str, hits)) + "\n") if hits else "-1\n"


def solve_topo(data: str) -> str:
    lines = data.splitlines()
    n, m = map(int, lines[0].split())
    graph: dict[int, list[int]] = {}
    indeg = [0] * (n + 1)
    for line in lines[1:1 + m]:
        a, b = map(int, line.split())
        graph.setdefault(a, []).append(b)
        indeg[b] += 1
    heap = [i for i in range(1, n + 1) if indeg[i] == 0]
    heapq.heapify(heap)
    order = []
    while heap:
        u = heapq.heappop(heap)
        order.append(u)
        for v in graph.get(u, []):
            indeg[v] -= 1
            if indeg[v] == 0:
                heapq.heappush(heap, v)
    if len(order) != n:
        return "-1\n"
    return " ".join(map(str, order)) + "\n"


# --------------------------------------------------------------------------
# 用例定义：problem_id -> (参考实现, 每个用例的输入)
# 输入文本一律以换行结尾（空输入除外）。
# --------------------------------------------------------------------------

def _lines(*rows: str) -> str:
    return "\n".join(rows) + "\n"


CASES: dict[int, tuple] = {
    1: (solve_two_sum, [
        _lines("4", "2 7 11 15", "9"),
        _lines("5", "3 2 4 6 1", "6"),
        _lines("6", "1 5 3 9 7 2", "14"),
        _lines("7", "10 20 30 40 50 60 70", "130"),
        _lines("8", "-3 4 3 90 -1 2 8 11", "0"),
    ]),
    2: (solve_brackets, [
        "()\n",
        "([)]\n",
        "{[()()]}\n",
        "(()\n",
    ]),
    3: (solve_lcp, [
        _lines("3", "flower", "flow", "flight"),
        _lines("3", "prefix", "preform", "prevent"),
        _lines("4", "interview", "internet", "internal", "interval"),
        _lines("1", "single"),
    ]),
    4: (solve_stairs, ["1\n", "2\n", "10\n", "45\n"]),
    5: (solve_max_subarray, [
        _lines("9", "-2 1 -3 4 -1 2 1 -5 4"),
        _lines("1", "5"),
        _lines("5", "-3 -1 -2 -5 -4"),
        _lines("5", "8 -19 5 -4 20"),
    ]),
    6: (solve_reverse_list, [
        _lines("5", "1 2 3 4 5"),
        _lines("1", "7"),
        _lines("4", "10 20 30 40"),
        _lines("6", "-1 0 1 2 3 4"),
    ]),
    7: (solve_tree_depth, [
        _lines("3", "1 2 0", "1 3 1"),
        "1\n",
        _lines("5", "1 2 0", "2 3 0", "3 4 0", "1 5 1"),
        _lines("7", "1 2 0", "1 3 1", "2 4 0", "2 5 1", "3 6 0", "3 7 1"),
    ]),
    8: (solve_binary_search, [
        _lines("6 9", "-1 0 3 5 9 12"),
        _lines("5 2", "1 2 3 4 5"),
        _lines("5 6", "1 2 3 4 5"),
        _lines("1 0", "0"),
    ]),
    9: (solve_coin_change, [
        _lines("3 11", "1 2 5"),
        _lines("1 3", "2"),
        _lines("2 7", "2 5"),
        _lines("4 27", "2 5 10 1"),
    ]),
    10: (solve_dijkstra, [
        _lines("3 3", "1 2 2", "2 3 1", "1 3 5"),
        _lines("4 2", "1 2 5", "3 4 1"),
        _lines("5 6", "1 2 10", "1 3 3", "3 2 1", "2 4 2", "3 4 8", "4 5 7"),
        "1 0\n",
    ]),
    11: (solve_mst, [
        _lines("4 5", "1 2 2", "1 3 3", "2 3 1", "2 4 4", "3 4 5"),
        _lines("2 1", "1 2 9"),
        _lines("5 6", "1 2 1", "2 3 2", "3 4 3", "4 5 4", "5 1 5", "1 3 6"),
        _lines("3 3", "1 2 4", "2 3 5", "1 3 6"),
    ]),
    12: (solve_pow, ["2 10\n", "3 0\n", "123456 1000000000\n", "999999999 3\n"]),
    13: (solve_permutations, [
        _lines("3", "1 2 3"),
        _lines("1", "5"),
        _lines("2", "2 1"),
        _lines("4", "4 1 3 2"),
    ]),
    14: (solve_kmp, [
        _lines("ababcabcabab", "ab"),
        _lines("aaaaa", "aa"),
        _lines("abc", "xyz"),
        _lines("mississippi", "issi"),
    ]),
    15: (solve_topo, [
        _lines("4 4", "1 2", "2 3", "1 3", "3 4"),
        _lines("3 3", "1 2", "2 3", "3 1"),
        _lines("5 4", "1 2", "1 3", "4 5", "3 5"),
        _lines("3 3", "1 2", "2 3", "1 3"),
    ]),
}

# 公开样例（与 problem_sample 表内容一致，case_order = 0，不计分）
SAMPLES: dict[int, tuple[int, str]] = {
    34: (1, _lines("4", "2 7 11 15", "9")),
    35: (2, "()[]{}\n"),
    36: (3, _lines("3", "flower", "flow", "flight")),
}

# problem_id -> (首个 case_id, 每个用例分值)
CASE_ID_START = {
    1: (1, 20), 2: (6, 25), 3: (10, 25), 4: (14, 25),
    5: (18, 25), 6: (22, 25), 7: (26, 25), 8: (30, 25),
    9: (37, 25), 10: (41, 25), 11: (45, 25), 12: (49, 25),
    13: (53, 25), 14: (57, 25), 15: (61, 25),
}


def build() -> list[tuple[int, int, int, int, int, str, str]]:
    """返回 (problem_id, case_id, case_order, score, case_type, input, output)。"""
    rows = []
    for pid, (solver, inputs) in CASES.items():
        first_id, score = CASE_ID_START[pid]
        for offset, raw in enumerate(inputs):
            case_id = first_id + offset
            rows.append((pid, case_id, offset + 1, score, 0, raw, solver(raw)))
    for case_id, (pid, raw) in SAMPLES.items():
        rows.append((pid, case_id, 0, 0, 1, raw, CASES[pid][0](raw)))
    return sorted(rows, key=lambda r: r[1])


def write_files(rows, check_only: bool) -> list[str]:
    problems = []
    for pid, case_id, _order, _score, _type, raw, expected in rows:
        case_dir = OUTPUT_ROOT / str(pid) / str(case_id)
        in_file, out_file = case_dir / "input.txt", case_dir / "output.txt"
        if check_only:
            actual = out_file.read_text(encoding="utf-8") if out_file.exists() else None
            if actual != expected or in_file.read_text(encoding="utf-8") != raw:
                problems.append(f"{pid}/{case_id}")
            continue
        case_dir.mkdir(parents=True, exist_ok=True)
        in_file.write_text(raw, encoding="utf-8")
        out_file.write_text(expected, encoding="utf-8")
    return problems


def render_sql(rows) -> str:
    out = [
        "-- 判题用例。用例内容与期望输出由 scripts/gen-demo-testcases.py 生成，",
        "-- 对应的 input.txt / output.txt 位于 OJ-backend/db/demo-testcases/。",
        "-- 安装：./scripts/db-seed.sh 会把该目录复制到 oj.testcase.base-path。",
        "-- case_type: 0 = 计分用例，1 = 公开样例（不计分）。",
        "INSERT INTO problem_case (id, problem_id, case_order, input_file_path, output_file_path, score, case_type, status) VALUES",
    ]
    lines = []
    for pid, case_id, order, score, case_type, _raw, _expected in rows:
        rel_in = f"{pid}/{case_id}/input.txt"
        rel_out = f"{pid}/{case_id}/output.txt"
        lines.append(f"({case_id}, {pid}, {order}, '{rel_in}', '{rel_out}', {score}, {case_type}, 1)")
    return out[0] + "\n" + "\n".join(out[1:]) + "\n" + ",\n".join(lines) + ";\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="生成演示题目测试数据")
    parser.add_argument("--sql-only", action="store_true", help="只打印 SQL，不写文件")
    parser.add_argument("--check", action="store_true", help="校验已有文件是否与参考实现一致")
    args = parser.parse_args()

    rows = build()
    if not args.sql_only:
        problems = write_files(rows, args.check)
        if args.check:
            if problems:
                print("不一致的用例: " + ", ".join(problems), file=sys.stderr)
                return 1
            print(f"校验通过：{len(rows)} 个用例与参考实现一致。")
            return 0
        print(f"已写入 {len(rows)} 个用例到 {OUTPUT_ROOT.relative_to(REPO_ROOT)}", file=sys.stderr)

    if not args.check:
        print(render_sql(rows))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
