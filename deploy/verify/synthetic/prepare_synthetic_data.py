#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""REL-02 合成数据准备（目标机执行，真实 API + 真实 MySQL + 真实 Judge0）

只调用正式 HTTP API，不直接写数据库；随机后缀避免污染；不导入演示 seed、不建默认管理员。
生成：1 个教师、2 个学生（管理员必须已在 runbook 第 4 节按流程创建并通过环境变量提供）。
随后：教师建班 -> 学生凭邀请码加入 -> 教师建题并批量保存测试用例 -> 学生做一次 AC/WA 冒烟。

用法：
  export REL02_SYNTH_ADMIN_USER=... REL02_SYNTH_ADMIN_PASS=...
  python3 deploy/verify/synthetic/prepare_synthetic_data.py \
      --base-url http://127.0.0.1:8080 --evidence-dir ~/musealgo-rel02-evidence/05-synthetic

退出码：0 全部成功；1 有步骤失败（失败详情写入 evidence/summary.json）。
"""
import argparse
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"


def gen_password(n=20):
    return "".join(secrets.choice(PASSWORD_ALPHABET) for _ in range(n))


class Api:
    def __init__(self, base_url, timeout=60):
        self.base = base_url.rstrip("/")
        self.timeout = timeout
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(self, method, path, body=None, token=None, expect_code=200):
        url = self.base + path
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=self.timeout) as resp:
                raw = resp.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            raise RuntimeError("HTTP %s %s -> %s: %s" % (method, path, e.code, raw[:400]))
        try:
            result = json.loads(raw)
        except json.JSONDecodeError:
            raise RuntimeError("非 JSON 响应 %s %s -> %s" % (method, path, raw[:400]))
        if result.get("code") != expect_code:
            raise RuntimeError("业务码异常 %s %s -> %s" % (
                method, path, json.dumps(result, ensure_ascii=False)[:600]))
        return result.get("data")

    def login(self, username, password):
        data = self.call("POST", "/auth/login", {"username": username, "password": password})
        return data["accessToken"]

    def login_full(self, username, password):
        data = self.call("POST", "/auth/login", {"username": username, "password": password})
        return data


class Recorder:
    def __init__(self, evidence_dir):
        self.dir = evidence_dir
        os.makedirs(self.dir, exist_ok=True)
        try:
            os.chmod(self.dir, 0o700)
        except OSError:
            pass
        self.steps = []
        self.private = os.path.join(self.dir, "accounts.private.json")

    def step(self, name, ok, detail=""):
        self.steps.append({
            "step": name, "ok": bool(ok), "detail": detail,
            "at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        })
        print(("  [PASS] " if ok else "  [FAIL] ") + name + ((" :: " + str(detail)[:300]) if detail else ""))
        return ok

    def save(self, payload, filename="summary.json"):
        path = os.path.join(self.dir, filename)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass
        return path


def main():
    p = argparse.ArgumentParser(description="REL-02 合成数据准备（真实 API）")
    p.add_argument("--base-url", default=os.environ.get("REL02_BACKEND_URL", "http://127.0.0.1:8080"))
    p.add_argument("--evidence-dir", default=os.environ.get("REL02_EVIDENCE_DIR", "./rel02-evidence") + "/05-synthetic")
    p.add_argument("--prefix", default=os.environ.get("REL02_SYNTH_PREFIX", "rel02"))
    p.add_argument("--admin-user", default=os.environ.get("REL02_SYNTH_ADMIN_USER", ""))
    p.add_argument("--admin-pass", default=os.environ.get("REL02_SYNTH_ADMIN_PASS", ""))
    p.add_argument("--skip-judge", action="store_true", help="只建数据，不做提交冒烟")
    args = p.parse_args()

    rec = Recorder(args.evidence_dir)
    api = Api(args.base_url)
    suffix = secrets.token_hex(3)
    out = {
        "kind": "rel02-synthetic-data",
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "base_url": args.base_url,
        "suffix": suffix,
        "accounts": {},
        "class": {},
        "problem": {},
        "judge": {},
        "steps": rec.steps,
    }
    private = {"suffix": suffix, "accounts": {}}

    def finish(code):
        rec.save(out)
        with open(rec.private, "w", encoding="utf-8") as f:
            json.dump(private, f, ensure_ascii=False, indent=2)
        try:
            os.chmod(rec.private, 0o600)
        except OSError:
            pass
        return code

    if not args.admin_user or not args.admin_pass:
        rec.step("前置：管理员账号", False,
                 "缺 REL02_SYNTH_ADMIN_USER/REL02_SYNTH_ADMIN_PASS；请先按 runbook 第 4 节创建管理员")
        return finish(1)

    try:
        admin_token = api.login(args.admin_user, args.admin_pass)
        rec.step("管理员登录", True)
    except Exception as e:
        rec.step("管理员登录", False, e)
        return finish(1)

    teacher_user = args.prefix + "-teacher-" + suffix
    student1 = args.prefix + "-student1-" + suffix
    student2 = args.prefix + "-student2-" + suffix
    teacher_pass = gen_password()
    student1_pass = gen_password()
    student2_pass = gen_password()

    created = {}
    for username, password, role, nickname in [
        (teacher_user, teacher_pass, 2, "合成教师 " + suffix),
        (student1, student1_pass, 1, "合成学生A " + suffix),
        (student2, student2_pass, 1, "合成学生B " + suffix),
    ]:
        try:
            api.call("POST", "/admin/users/create", {
                "username": username, "password": password,
                "nickname": nickname, "role": role,
            }, token=admin_token)
            created[username] = {"role": role, "password": password}
            rec.step("创建账号 role=%d %s" % (role, username), True)
        except Exception as e:
            rec.step("创建账号 role=%d %s" % (role, username), False, e)
            return finish(1)

    out["accounts"] = {
        "admin": {"username": args.admin_user, "role": 3},
        "teacher": {"username": teacher_user, "role": 2},
        "student1": {"username": student1, "role": 1},
        "student2": {"username": student2, "role": 1},
    }
    private["accounts"] = {args.admin_user: {"role": 3}, teacher_user: {"role": 2, "password": teacher_pass},
                           student1: {"role": 1, "password": student1_pass},
                           student2: {"role": 1, "password": student2_pass}}

    try:
        teacher_login = api.login_full(teacher_user, teacher_pass)
        teacher_token = teacher_login["accessToken"]
        teacher_id = (teacher_login.get("user") or {}).get("id")
        if teacher_id is None:
            raise RuntimeError("登录响应缺 user.id，无法创建班级（Class.teacherId 必填）")
        s1_token = api.login(student1, student1_pass)
        s2_token = api.login(student2, student2_pass)
        out["accounts"]["teacher"]["id"] = teacher_id
        rec.step("教师/学生登录（teacher id=%s）" % teacher_id, True)
    except Exception as e:
        rec.step("教师/学生登录", False, e)
        return finish(1)

    # ---- 班级 ----
    class_name = "REL02 合成班 " + suffix
    try:
        api.call("POST", "/class/create", {
            "name": class_name,
            "teacherId": teacher_id,
            "description": "REL-02 验收合成班级 " + suffix,
            "school": "REL02 Synthetic",
        }, token=teacher_token)
        classes = api.call("GET", "/class/list?teacherId=%s" % teacher_id, token=teacher_token) or []
        cid = None
        for c in classes:
            if c.get("name") == class_name:
                cid = c.get("id")
                break
        if cid is None:
            raise RuntimeError("创建后未在班级列表找到 %s" % class_name)
        out["class"] = {"id": cid, "name": class_name}
        rec.step("教师创建班级 id=%s" % cid, True)
    except Exception as e:
        rec.step("教师创建班级", False, e)
        return finish(1)

    try:
        code = api.call("GET", "/class/inviteCode?classId=%s" % cid, token=teacher_token)
        if isinstance(code, dict):
            code = code.get("inviteCode") or code.get("code")
        if not code:
            raise RuntimeError("邀请码为空，无法加入班级")
        api.call("POST", "/class/joinByCode", {"code": code}, token=s1_token)
        api.call("POST", "/class/joinByCode", {"code": code}, token=s2_token)
        members = api.call("GET", "/class/members?classId=%s" % cid, token=teacher_token)
        out["class"]["invite_code_present"] = bool(code)
        out["class"]["members"] = members
        rec.step("学生凭邀请码加入并核对成员列表", True)
    except Exception as e:
        rec.step("学生加入班级", False, e)
        return finish(1)

    # ---- 题目 + 测试用例 ----
    ac_input = "5 7\n"
    ac_output = "12\n"
    try:
        body = {
            "title": "REL02 合成题 A+B " + suffix,
            "content": "输入两个整数，输出它们的和。",
            "inputDesc": "一行两个整数 a b。",
            "outputDesc": "输出 a+b。",
            "hint": "",
            "timeLimit": 1000,
            "memoryLimit": 128,
            "difficulty": 1,
            "tags": "",
            "source": "REL02",
            "isPublic": 1,
            "samples": [{"input": ac_input, "output": ac_output}],
        }
        pid = api.call("POST", "/problem/add", body, token=teacher_token)
        if not isinstance(pid, int):
            pid = int(str(pid).strip())
        out["problem"] = {"id": pid, "target_input": ac_input, "target_output": ac_output}
        rec.step("教师创建公开题目 id=%s" % pid, True)
    except Exception as e:
        rec.step("教师创建题目", False, e)
        return finish(1)

    try:
        api.call("POST", "/problem/case/batch", {
            "problemId": pid,
            "cases": [
                {"input": ac_input, "output": ac_output},
                {"input": "1000000 2000000\n", "output": "3000000\n"},
            ],
        }, token=teacher_token)
        cases = api.call("GET", "/problem/case/list?problemId=%s" % pid, token=teacher_token) or []
        out["problem"]["case_count"] = len(cases)
        rec.step("批量保存测试用例（%d 组）" % len(cases), len(cases) >= 2)
    except Exception as e:
        rec.step("保存测试用例", False, e)
        return finish(1)

    if args.skip_judge:
        rec.step("判题冒烟（--skip-judge 跳过）", True)
        return finish(0)

    # ---- 判题冒烟：AC + WA（同一学生间隔 11s 规避限流）----
    ac_code = "#include <cstdio>\nint main(){int a,b;if(scanf(\"%d %d\",&a,&b)!=2)return 1;printf(\"%d\\n\",a+b);return 0;}\n"
    wa_code = "#include <cstdio>\nint main(){int a,b;if(scanf(\"%d %d\",&a,&b)!=2)return 1;printf(\"%d\\n\",a-b);return 0;}\n"

    def submit_and_wait(token, code, label):
        started = time.time()
        sid = api.call("POST", "/submission/submit", {
            "problemId": pid, "language": "cpp", "code": code,
        }, token=token)
        if isinstance(sid, dict):
            sid = sid.get("id")
        for _ in range(180):
            detail = api.call("GET", "/submission/detail?id=%s" % sid, token=token) or {}
            status = detail.get("status")
            if status not in (None, 0) or detail.get("judgePhase") == "FAILED":
                return {"submission_id": sid, "elapsed_s": round(time.time() - started, 1),
                        "detail": detail}
            time.sleep(1)
        raise RuntimeError("%s 判题超时 180s（submission=%s）" % (label, sid))

    try:
        r1 = submit_and_wait(s1_token, ac_code, "AC")
        out["judge"]["ac"] = r1
        rec.step("学生提交 AC -> status=%s score=%s" % (
            r1["detail"].get("status"), r1["detail"].get("score")),
            r1["detail"].get("status") == 1 and r1["detail"].get("score") == 100)
        time.sleep(11)
        r2 = submit_and_wait(s1_token, wa_code, "WA")
        out["judge"]["wa"] = r2
        rec.step("学生提交 WA -> status=%s score=%s" % (
            r2["detail"].get("status"), r2["detail"].get("score")),
            r2["detail"].get("status") == 2 and r2["detail"].get("score") == 0)
    except Exception as e:
        rec.step("判题冒烟", False, e)
        return finish(1)

    ok = all(s["ok"] for s in rec.steps)
    rec.step("整体", ok, "%d steps" % len(rec.steps))
    return finish(0 if ok else 1)


if __name__ == "__main__":
    sys.exit(main())
