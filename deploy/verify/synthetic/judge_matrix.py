#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""REL-02 Judge0 判题矩阵（目标机执行，真实 API + 真实 Judge0）

矩阵：Java/C/C++/Python 的 AC、WA、CE、TLE、内存边界。
- 通过真实 /submission/submit 提交并轮询 /submission/detail；
- 每个用例记录原始 detail（status/judgePhase/score/runTime/runMemory/errorMessage）；
- 状态码契约（SubmissionStatus，持久化码）：0=PENDING 1=AC 2=WA 3=CE 4=RE 5=TLE。
  **没有独立 MLE 状态**：Judge0 的 7(MLE) 归入 RUNTIME_ERROR(4)，内存边界以 RE + runMemory 为证据。

用法：
  python3 deploy/verify/synthetic/judge_matrix.py \
    --base-url http://127.0.0.1:8080 \
    --accounts-file ~/musealgo-rel02-evidence/05-synthetic/accounts.private.json \
    --evidence-dir ~/musealgo-rel02-evidence/08-judge \
    --interval 11
退出码：0 全部符合期望；1 有不符合项或执行失败。
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

CPP_AC = """#include <cstdio>
int main(){long long a,b;if(scanf("%lld %lld",&a,&b)!=2)return 1;printf("%lld\n",a+b);return 0;}
"""
C_AC = """#include <stdio.h>
int main(void){long long a,b;if(scanf("%lld %lld",&a,&b)!=2)return 1;printf("%lld\n",a+b);return 0;}
"""
JAVA_AC = """import java.util.Scanner;
public class Main {
  public static void main(String[] args) {
    Scanner sc = new Scanner(System.in);
    long a = sc.nextLong(), b = sc.nextLong();
    System.out.println(a + b);
  }
}
"""
PY_AC = """import sys
data = sys.stdin.read().split()
if len(data) < 2:
    sys.exit(1)
print(int(data[0]) + int(data[1]))
"""
CPP_WA = """#include <cstdio>
int main(){long long a,b;if(scanf("%lld %lld",&a,&b)!=2)return 1;printf("%lld\n",a-b);return 0;}
"""
CPP_CE = """#include <cstdio>
int main( { this is not valid c++ }
"""
CPP_TLE = """#include <cstdio>
int main(){volatile unsigned long long x=0;for(;;){x++;}return 0;}
"""
CPP_MEM = """#include <cstdio>
#include <cstdlib>
#include <cstring>
int main(){
  const size_t n = 512UL * 1024UL * 1024UL;
  char* p = (char*)malloc(n);
  if(!p) return 0;
  memset(p, 1, n);
  printf("%d\n", (int)p[0]);
  return 0;
}
"""


class Api:
    def __init__(self, base_url, timeout=120):
        self.base = base_url.rstrip("/")
        self.timeout = timeout
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(self, method, path, body=None, token=None):
        headers = {"Accept": "application/json"}
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=self.timeout) as resp:
                raw = resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            raise RuntimeError("HTTP %s %s -> %s: %s" % (method, path, e.code, raw[:400]))
        result = json.loads(raw)
        if result.get("code") != 200:
            raise RuntimeError("业务码 %s %s -> %s" % (method, path, json.dumps(result, ensure_ascii=False)[:500]))
        return result.get("data")

    def login(self, username, password):
        return self.call("POST", "/auth/login", {"username": username, "password": password})["accessToken"]


def build_problem(api, token, title, time_limit_ms, memory_limit_mb):
    body = {
        "title": title, "content": "REL02 judge matrix", "inputDesc": "a b",
        "outputDesc": "a+b", "hint": "", "timeLimit": time_limit_ms,
        "memoryLimit": memory_limit_mb, "difficulty": 1, "tags": "",
        "source": "REL02-judge-matrix", "isPublic": 1,
        "samples": [{"input": "5 7\n", "output": "12\n"}],
    }
    pid = api.call("POST", "/problem/add", body, token=token)
    if not isinstance(pid, int):
        pid = int(str(pid).strip())
    api.call("POST", "/problem/case/batch", {
        "problemId": pid,
        "cases": [{"input": "5 7\n", "output": "12\n"},
                  {"input": "-3 9\n", "output": "6\n"}],
    }, token=token)
    return pid


def submit_and_wait(api, token, problem_id, language, code, timeout_s=180):
    started = time.time()
    data = api.call("POST", "/submission/submit", {
        "problemId": problem_id, "language": language, "code": code,
    }, token=token)
    sid = data.get("id") if isinstance(data, dict) else data
    last = {}
    while time.time() - started < timeout_s:
        detail = api.call("GET", "/submission/detail?id=%s" % sid, token=token) or {}
        last = detail
        if detail.get("status") not in (None, 0) or detail.get("judgePhase") == "FAILED":
            return {"submission_id": sid, "elapsed_s": round(time.time() - started, 1), "detail": detail}
        time.sleep(1)
    return {"submission_id": sid, "elapsed_s": round(time.time() - started, 1),
            "detail": last, "timeout": True}


def main():
    p = argparse.ArgumentParser(description="REL-02 Judge0 判题矩阵")
    p.add_argument("--base-url", default=os.environ.get("REL02_BACKEND_URL", "http://127.0.0.1:8080"))
    p.add_argument("--accounts-file", default="")
    p.add_argument("--teacher-user", default=os.environ.get("REL02_TEACHER_USER", ""))
    p.add_argument("--teacher-pass", default=os.environ.get("REL02_TEACHER_PASS", ""))
    p.add_argument("--student-user", default=os.environ.get("REL02_STUDENT_USER", ""))
    p.add_argument("--student-pass", default=os.environ.get("REL02_STUDENT_PASS", ""))
    p.add_argument("--evidence-dir", default=os.environ.get("REL02_EVIDENCE_DIR", "./rel02-evidence") + "/08-judge")
    p.add_argument("--interval", type=int, default=int(os.environ.get("REL02_SUBMIT_INTERVAL_SECONDS", "11")))
    p.add_argument("--timeout", type=int, default=180)
    args = p.parse_args()

    os.makedirs(args.evidence_dir, exist_ok=True)
    try:
        os.chmod(args.evidence_dir, 0o700)
    except OSError:
        pass

    teacher_user, teacher_pass = args.teacher_user, args.teacher_pass
    student_user, student_pass = args.student_user, args.student_pass
    if args.accounts_file and os.path.isfile(args.accounts_file):
        with open(args.accounts_file, encoding="utf-8") as f:
            acc = json.load(f).get("accounts", {})
        for name, info in acc.items():
            role = info.get("role")
            if role == 2 and not teacher_user:
                teacher_user, teacher_pass = name, info.get("password")
            if role == 1 and not student_user:
                student_user, student_pass = name, info.get("password")
    if not teacher_user or not student_user:
        print("缺教师或学生凭据：用 --accounts-file 或 REL02_TEACHER_USER/PASS + REL02_STUDENT_USER/PASS", file=sys.stderr)
        return 1

    api = Api(args.base_url)
    report = {
        "kind": "rel02-judge-matrix",
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "base_url": args.base_url,
        "interval_s": args.interval,
        "status_contract": {
            "0": "PENDING", "1": "ACCEPTED", "2": "WRONG_ANSWER",
            "3": "COMPILATION_ERROR", "4": "RUNTIME_ERROR", "5": "TIME_LIMIT_EXCEEDED",
            "note": "无独立 MLE 状态；Judge0 状态 7(MLE) 归入 RUNTIME_ERROR(4)",
        },
        "problems": {}, "cases": [],
    }

    try:
        teacher_token = api.login(teacher_user, teacher_pass)
        student_token = api.login(student_user, student_pass)
    except Exception as e:
        print("登录失败：%s" % e, file=sys.stderr)
        return 1

    try:
        pid_std = build_problem(api, teacher_token, "REL02-JUDGE-STD", 1000, 128)
        pid_mem = build_problem(api, teacher_token, "REL02-JUDGE-MEM", 1000, 128)
        report["problems"] = {"std": pid_std, "mem": pid_mem}
        print("题目已创建：std=%s mem=%s" % (pid_std, pid_mem))
    except Exception as e:
        print("创建判题题目失败：%s" % e, file=sys.stderr)
        return 1

    cases = [
        ("JUDGE-01", "java", "AC", pid_std, JAVA_AC, lambda d: d.get("status") == 1 and d.get("score") == 100),
        ("JUDGE-02", "c", "AC", pid_std, C_AC, lambda d: d.get("status") == 1),
        ("JUDGE-03", "cpp", "AC", pid_std, CPP_AC, lambda d: d.get("status") == 1),
        ("JUDGE-04", "python", "AC", pid_std, PY_AC, lambda d: d.get("status") == 1),
        ("JUDGE-05", "cpp", "WA", pid_std, CPP_WA, lambda d: d.get("status") == 2 and d.get("score") == 0),
        ("JUDGE-06", "cpp", "CE", pid_std, CPP_CE, lambda d: d.get("status") == 3),
        ("JUDGE-07", "cpp", "TLE", pid_std, CPP_TLE, lambda d: d.get("status") == 5),
        ("JUDGE-08", "cpp", "MEMORY", pid_mem, CPP_MEM,
         lambda d: d.get("status") == 4 and (d.get("runMemory") or 0) > 0),
    ]

    failures = 0
    first = True
    for cid, lang, label, pid, code, check in cases:
        if not first:
            print("  等待 %ds 规避提交限流..." % args.interval)
            time.sleep(args.interval)
        first = False
        record = {"id": cid, "language": lang, "expect": label, "problem_id": pid}
        try:
            r = submit_and_wait(api, student_token, pid, lang, code, args.timeout)
            detail = r["detail"]
            passed = bool(check(detail))
            record.update({
                "submission_id": r["submission_id"], "elapsed_s": r["elapsed_s"],
                "status": detail.get("status"), "judgePhase": detail.get("judgePhase"),
                "score": detail.get("score"), "runTime": detail.get("runTime"),
                "runMemory": detail.get("runMemory"),
                "errorMessage": (detail.get("errorMessage") or "")[:400],
                "passed": passed,
            })
            if not passed:
                failures += 1
            print(("  [PASS] " if passed else "  [FAIL] ") + "%s %s -> status=%s score=%s time=%s mem=%s" % (
                cid, label, record["status"], record["score"], record["runTime"], record["runMemory"]))
        except Exception as e:
            record.update({"passed": False, "error": str(e)})
            failures += 1
            print("  [FAIL] %s %s -> %s" % (cid, label, e))
        report["cases"].append(record)

    report["summary"] = {"total": len(cases), "passed": len(cases) - failures, "failed": failures}
    path = os.path.join(args.evidence_dir, "matrix.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass
    print("结果写入 %s（passed=%d failed=%d）" % (path, report["summary"]["passed"], failures))
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
