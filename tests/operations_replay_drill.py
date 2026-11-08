#!/usr/bin/env python3
"""OPS-01 settlement replay maintenance drill on a disposable local stack.

Exercises the existing chain-replay / reverse-restore implementation on a brand-new
musealgo-replay-* Compose project (MySQL 8.0 + Redis 6.0 from tests/compose.yml)
plus a temporary oj-admin jar process.  It never touches existing projects,
business volumes or fixed ports, and removes every container/volume/network it
creates in a finally block.

Measured scenarios:
  config   maintenance switch defaults off; switch-off preview/execute refused
  M4       field-by-field replay -> restore reconciliation (3-contest chain)
  M5a      reverse restore refused after a later write (fingerprint drift)
  M5b      reverse restore refused when a newer successful replay exists
  M6       backup read permission (admin vs student) + audit_log evidence
  M7       mid-transaction failure injection -> whole transaction rolled back;
           pending submission blocks replay before any write
  M2       concurrent execute requests serialize through the settlement guard
  M3       guard lock wait measured; execute/restore transaction duration
  M1       50-contest / 20000-row protection boundaries and over-limit refusal

Usage (requires JDK17 and a built oj-admin jar):
  python3 tests/operations_replay_drill.py --output /tmp/musealgo-replay-evidence
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
PARTICIPANTS = [4, 5, 6, 7, 8, 9]
ADMIN_USER = 'admin'
STUDENT_USER = 'student01'
SCENARIOS = [
    {'id': 'config', 'title': '维护开关默认关闭；开关关闭时预览不可执行、执行被拒绝',
     'asserts': ['preview.warnings 含 链式重放维护开关未开启', 'preview.canExecute=false',
                 'execute 业务码非 200 且 replay 行数不变'], 'expected_minutes': 1},
    {'id': 'M4', 'title': '3 场比赛链式重放 -> 逐字段反向恢复对账',
     'asserts': ['preview.canExecute=true 且链=3/人数=6/旧行=18', '空原因=400、过期确认串被拒且不落库',
                 '重放后 settlement_no 全为 2、record=COMPLETED、rating 幂等',
                 '恢复后 contest_rank/user_rating/contest_settlement/user.rating 逐字段等于重放前',
                 'record=RESTORED、二次恢复被拒'], 'expected_minutes': 3},
    {'id': 'M5a', 'title': '重放后存在后续写入时拒绝反向恢复',
     'asserts': ['restore-preview.canExecute=false 且 warnings 含 重放后数据已变化',
                 'restore 被拒且数据零写入'], 'expected_minutes': 2},
    {'id': 'M5b', 'title': '只允许恢复最近一条成功重放',
     'asserts': ['第一次重放后 settlement_no=2，第二次=3', '旧记录恢复预览含 只能恢复最近一条 警告且不可执行',
                 '最新记录恢复成功并逐字段对账'], 'expected_minutes': 3},
    {'id': 'M2', 'title': '并发 execute 经全局结算锁串行化',
     'asserts': ['两个并发请求恰好一成一败', '仅 1 条 COMPLETED 记录、settlement_no 只加 1',
                 '失败方为确认串/预览失效错误'], 'expected_minutes': 2},
    {'id': 'M3', 'title': '锁等待与事务耗时实测',
     'asserts': ['外部事务占用 contest_settlement_guard 行锁时忙等 >0.5s',
                 '汇总 execute/restore 事务耗时与 MySQL 变量'], 'expected_minutes': 1},
    {'id': 'M6', 'title': '备份读取权限与审计口径',
     'asserts': ['管理员可读 backupJson 且写入 read_replay_backup 审计',
                 '学生 403 且无成功读取审计', 'history 列表不含 backupJson',
                 '预览接口无审计（记录 G7 遗留）', '停写窗口内恢复：持锁 2.5s 时 restore 阻塞后成功'],
     'expected_minutes': 3},
    {'id': 'M7', 'title': '事务中途失败整体回滚 + 待判提交前置拒绝',
     'asserts': ['注入 BEFORE DELETE 触发器失败后 rank/流水/当前分/记录零残留',
                 '失败尝试存在于 audit_log(status=0)', '待判提交预览警告且执行被拒、数据不变'],
     'expected_minutes': 3},
    {'id': 'M1', 'title': '50 场 / 20000 行保护边界与超限拒绝',
     'asserts': ['恰好 50 场可执行、51 场拒绝', '恰好 20000 行不触发行数上限、20001 行拒绝',
                 '超限 execute 被拒且不落库'], 'expected_minutes': 5},
]


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def sha256_text(text):
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def canonical(value):
    return sha256_text(json.dumps(value, sort_keys=True, separators=(',', ':')))


def now_iso():
    return dt.datetime.now().astimezone().isoformat(timespec='seconds')


def count_rows(text):
    return 0 if not text else len(text.splitlines())


def require(condition, message):
    if not condition:
        raise AssertionError(message)


class ReplayDrill:
    def __init__(self, args):
        self.args = args
        self.output = Path(args.output).resolve()
        self.output.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.output.chmod(0o700)
        self._temporary = tempfile.TemporaryDirectory(prefix='musealgo-replay-')
        self.temporary = Path(self._temporary.name)
        compose_text = (ROOT / 'tests/compose.yml').read_text()
        self.base_env = os.environ.copy()
        for name in re.findall(r'\$\{([A-Za-z_][A-Za-z0-9_]*)', compose_text):
            self.base_env.pop(name, None)
        self.project = 'musealgo-replay-' + secrets.token_hex(5)
        self.db_password = secrets.token_hex(32)
        self.mq_password = secrets.token_hex(32)
        self.mysql_port = str(free_port())
        self.redis_port = str(free_port())
        self.compose_file = ROOT / 'tests/compose.yml'
        self.env_file = None
        self.processes = []
        self.current_log = None
        self.api_base = None
        self.admin_token = None
        self.student_token = None
        self.admin_id = None
        self.student_id = None
        self.m2_replay_id = None
        self.m2_chain = None
        self.jar = Path(args.jar).resolve() if args.jar else (ROOT / 'OJ-Backend/oj-admin/target/oj-admin-0.1.0-SNAPSHOT.jar')
        self.started_at = now_iso()
        self.evidence = {
            'task': 'OPS-01',
            'scenario': 'settlement replay maintenance drill (local disposable stack)',
            'started_at': self.started_at,
            'project': self.project,
            'environment': {
                'project': self.project,
                'mysql_port': self.mysql_port,
                'redis_port': self.redis_port,
                'participants': PARTICIPANTS,
            },
            'config': {},
            'checks': {},
            'metrics': [],
            'cleanup': {},
        }

    # ------------------------------------------------------------------ helpers
    def compose_command(self, parts):
        return ['docker', 'compose', '--env-file', str(self.env_file), '-p', self.project,
                '-f', str(self.compose_file)] + list(parts)

    def compose_ok(self, *parts, timeout=600):
        proc = subprocess.run(self.compose_command(parts), env=self.base_env,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if proc.returncode != 0:
            raise RuntimeError('docker compose %s failed: %s' % (' '.join(parts), proc.stderr.decode(errors='replace')[-4000:]))
        return proc

    def mysql_exec(self, script, timeout=300):
        command = self.compose_command(['exec', '-T', 'mysql', 'sh', '-c',
                                        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B oj_db'])
        proc = subprocess.run(command, input=script.encode('utf-8'), env=self.base_env,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if proc.returncode != 0:
            raise RuntimeError('SQL failed: %s\nstatement: %s' % (proc.stderr.decode(errors='replace')[-4000:], script[:2000]))
        return proc.stdout.decode('utf-8').strip()

    def sql(self, script):
        return self.mysql_exec(script)

    def java_binary(self):
        candidates = [os.environ.get('JAVA_BIN')]
        java_home = os.environ.get('JAVA_HOME')
        if java_home:
            candidates.append(str(Path(java_home) / 'bin' / 'java'))
        candidates.extend(['/opt/homebrew/opt/openjdk@17/bin/java', shutil.which('java')])
        for candidate in candidates:
            if candidate and Path(candidate).is_file() and os.access(candidate, os.X_OK):
                return candidate
        raise RuntimeError('JDK 17 java binary not found; set JAVA_BIN')

    def record(self, name, **data):
        self.evidence['checks'][name] = data
        print('[ops01] %s: %s' % (name, json.dumps(data, ensure_ascii=False)[:600]), flush=True)

    def metric(self, **item):
        self.evidence['metrics'].append(item)

    def backend_log_line(self, *needles):
        if not self.current_log or not Path(self.current_log).exists():
            return None
        text = Path(self.current_log).read_text(errors='replace')
        for line in reversed(text.splitlines()):
            for needle in needles:
                if needle in line:
                    return line.strip()[:600]
        return None

    # ------------------------------------------------------------------- stack
    def assert_environment(self):
        require(self.jar.is_file(), 'oj-admin jar missing: %s (build first or pass --jar)' % self.jar)
        docker_version = subprocess.run(['docker', 'version', '--format', '{{.Server.Version}}'],
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode().strip()
        java_version = subprocess.run([self.java_binary(), '-version'], stdout=subprocess.PIPE,
                                      stderr=subprocess.STDOUT).stdout.decode(errors='replace').strip().splitlines()[0]
        self.evidence['environment'].update({
            'docker_server': docker_version,
            'java': java_version,
            'python': sys.version.split()[0],
            'jar': str(self.jar),
            'jar_bytes': self.jar.stat().st_size,
            'jar_sha256': sha256_file(self.jar),
        })

    def start_stack(self):
        self.env_file = self.temporary / 'stack.env'
        self.env_file.write_text('\n'.join([
            'OJ_CI_DB_PASSWORD=' + self.db_password,
            'OJ_CI_MYSQL_PORT=' + self.mysql_port,
            'OJ_CI_REDIS_PORT=' + self.redis_port,
            'OJ_CI_MQ_PASSWORD=' + self.mq_password,
        ]) + '\n')
        self.env_file.chmod(0o600)
        began = time.monotonic()
        self.compose_ok('up', '-d', '--wait', 'mysql', 'redis', timeout=600)
        compose_ms = round((time.monotonic() - began) * 1000, 1)
        seed = (ROOT / 'OJ-Backend/db/seed.sql').read_bytes()
        command = self.compose_command(['exec', '-T', 'mysql', 'sh', '-c',
                                        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot oj_db'])
        proc = subprocess.run(command, input=seed, env=self.base_env,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=300)
        require(proc.returncode == 0, 'seed import failed: %s' % proc.stderr.decode(errors='replace')[-2000:])
        self.evidence['environment']['compose_up_ms'] = compose_ms
        self.evidence['environment']['seed_loaded'] = True

    def boot_backend(self, replay_enabled):
        port = str(free_port())
        label = 'on' if replay_enabled else 'off'
        log_path = self.output / ('backend-replay-%s.log' % label)
        env = self.base_env.copy()
        env.update({
            'TZ': 'Asia/Shanghai',
            'SERVER_PORT': port,
            'SPRING_DATASOURCE_URL': ('jdbc:mysql://127.0.0.1:%s/oj_db?useUnicode=true&characterEncoding=UTF-8'
                                      '&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true' % self.mysql_port),
            'OJ_DB_PASS': self.db_password,
            'SPRING_DATA_REDIS_PORT': self.redis_port,
            'JWT_SECRET': secrets.token_hex(48),
            'DEEPSEEK_API_KEY': '',
            'COS_SECRET_ID': '',
            'COS_SECRET_KEY': '',
            'OJ_SEARCH_ENABLED': 'false',
            'OJ_RABBITMQ_ENABLED': 'false',
            'MANAGEMENT_HEALTH_RABBIT_ENABLED': 'false',
            'OJ_JUDGE_WORKER_ENABLED': 'false',
            'OJ_SETTLEMENT_REPLAY_ENABLED': 'true' if replay_enabled else 'false',
            'OJ_CONTEST_SETTLEMENT_INITIAL_DELAY_MS': '86400000',
            'OJ_CONTEST_SETTLEMENT_INTERVAL_MS': '86400000',
            'OJ_TESTCASE_PATH': str(ROOT / 'OJ-Backend/db/demo-testcases'),
            'OJ_UPLOAD_DIR': str(self.temporary / 'uploads'),
            'OJ_SLIDEV_OUTPUT_DIR': str(self.temporary / 'coursewares'),
        })
        handle = log_path.open('wb')
        proc = subprocess.Popen([self.java_binary(), '-Xmx1g', '-jar', str(self.jar)],
                                cwd=str(ROOT), env=env, stdout=handle, stderr=subprocess.STDOUT,
                                start_new_session=True)
        handle.close()
        self.processes.append(proc)
        self.current_log = log_path
        self.api_base = 'http://127.0.0.1:%s' % port
        began = time.monotonic()
        deadline = began + 240
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                raise RuntimeError('backend exited before becoming healthy: %s' % log_path)
            try:
                with HTTP.open(self.api_base + '/actuator/health', timeout=3) as response:
                    if json.load(response).get('status') == 'UP':
                        self.evidence['environment']['backend_boot_%s' % label] = {
                            'port': port,
                            'log': str(log_path),
                            'replay_enabled': replay_enabled,
                            'boot_ms': round((time.monotonic() - began) * 1000, 1),
                        }
                        return proc
            except (OSError, ValueError):
                pass
            time.sleep(1)
        raise RuntimeError('backend readiness timeout: %s' % log_path)

    def stop_backend(self, proc):
        if proc is not None and proc.poll() is None:
            os.killpg(proc.pid, signal.SIGTERM)
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL)
                proc.wait()

    def cleanup(self):
        for proc in reversed(self.processes):
            try:
                self.stop_backend(proc)
            except Exception:
                pass
        if self.env_file is not None and self.env_file.exists():
            try:
                with (self.output / 'containers.log').open('wb') as handle:
                    subprocess.run(self.compose_command(['logs', '--no-color']), env=self.base_env,
                                   stdout=handle, stderr=subprocess.STDOUT, timeout=120)
            except Exception as error:
                self.evidence['cleanup']['logs_error'] = str(error)
            down = subprocess.run(self.compose_command(['down', '-v', '--remove-orphans']), env=self.base_env,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=300)
            containers = subprocess.run(['docker', 'ps', '-a', '--filter',
                                         'label=com.docker.compose.project=' + self.project,
                                         '--format', '{{.Names}}'],
                                        stdout=subprocess.PIPE).stdout.decode().strip()
            volumes = subprocess.run(['docker', 'volume', 'ls', '--filter',
                                      'label=com.docker.compose.project=' + self.project,
                                      '--format', '{{.Name}}'],
                                     stdout=subprocess.PIPE).stdout.decode().strip()
            self.evidence['cleanup'].update({
                'down_returncode': down.returncode,
                'down_stderr': down.stderr.decode(errors='replace')[-1000:],
                'leftover_containers': containers.splitlines(),
                'leftover_volumes': volumes.splitlines(),
            })
        self._temporary.cleanup()

    # --------------------------------------------------------------------- api
    def api(self, path, method='GET', body=None, token=None, timeout=180):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(self.api_base + path, data=data, headers=headers, method=method)
        try:
            with HTTP.open(request, timeout=timeout) as response:
                return response.status, json.loads(response.read().decode('utf-8') or '{}')
        except urllib.error.HTTPError as error:
            raw = error.read().decode('utf-8', errors='replace')
            try:
                return error.code, json.loads(raw)
            except ValueError:
                return error.code, {'raw': raw[:2000]}

    def login(self, username):
        status, payload = self.api('/auth/login', method='POST', body={'username': username, 'password': '123456'})
        require(status == 200 and payload.get('code') == 200, 'login %s failed: %s %s' % (username, status, payload))
        data = payload['data']
        return data['accessToken'], data.get('user', {}).get('id')

    def bootstrap_tokens(self):
        self.admin_token, self.admin_id = self.login(ADMIN_USER)
        self.student_token, self.student_id = self.login(STUDENT_USER)

    def preview(self, from_id):
        began = time.monotonic()
        status, payload = self.api('/admin/settlement-replay/%d/preview' % from_id, token=self.admin_token)
        return status, payload, round((time.monotonic() - began) * 1000, 1)

    def preview_ok(self, from_id):
        status, payload, duration = self.preview(from_id)
        require(status == 200 and payload.get('code') == 200, 'preview %d failed: %s %s' % (from_id, status, payload))
        return payload['data'], duration

    def execute(self, from_id, reason, confirm):
        began = time.monotonic()
        status, payload = self.api('/admin/settlement-replay/%d' % from_id, method='POST',
                                   body={'reason': reason, 'confirm': confirm}, token=self.admin_token)
        return status, payload, round((time.monotonic() - began) * 1000, 1)

    def restore_preview(self, replay_id):
        began = time.monotonic()
        status, payload = self.api('/admin/settlement-replay/history/%d/restore-preview' % replay_id,
                                   token=self.admin_token)
        return status, payload, round((time.monotonic() - began) * 1000, 1)

    def restore(self, replay_id, reason, confirm):
        began = time.monotonic()
        status, payload = self.api('/admin/settlement-replay/history/%d/restore' % replay_id, method='POST',
                                   body={'reason': reason, 'confirm': confirm}, token=self.admin_token)
        return status, payload, round((time.monotonic() - began) * 1000, 1)

    # ----------------------------------------------------------------- fixtures
    def prepare_seed_settlements(self):
        self.sql('UPDATE contest SET rank_finalized_at = COALESCE(end_time, update_time, NOW()) '
                 'WHERE id = 2 AND rank_finalized_at IS NULL;')
        self.sql("INSERT IGNORE INTO contest_settlement (contest_id,algorithm_version,rating_algorithm_version,"
                 "settlement_no,finalized_at,create_time,update_time) "
                 "SELECT id,'v1','elo-v1',1,rank_finalized_at,NOW(),NOW() FROM contest "
                 "WHERE id = 2 AND rank_finalized_at IS NOT NULL;")

    def create_chain(self, first_id, count, end_base, title_prefix):
        rows = []
        for index in range(count):
            contest_id = first_id + index
            end = end_base + dt.timedelta(minutes=index)
            start = end - dt.timedelta(hours=2)
            rows.append("(%d,'%s #%d','%s','%s',120,'ACM','Public',2,'ops01-fixture',0,1,0,0,'FORMAL',"
                        "'2026-02-01 00:00:00','2026-02-01 00:00:00')" % (
                            contest_id, title_prefix, index + 1,
                            start.strftime('%Y-%m-%d %H:%M:%S'), end.strftime('%Y-%m-%d %H:%M:%S')))
        self.sql('INSERT INTO contest (id,title,start_time,end_time,duration,mode,type,author_id,author_name,'
                 'participant_count,visible,is_deleted,plagiarism_checked,category,create_time,update_time) VALUES '
                 + ','.join(rows) + ';')
        ids = [first_id + index for index in range(count)]
        id_list = ','.join(str(item) for item in ids)
        self.sql("INSERT INTO contest_user (contest_id,user_id,status,eligible,rating_at_registration,create_time,update_time) "
                 "SELECT c.id, u.id, 'AUTO_APPROVED', 1, u.rating, NOW(), NOW() FROM contest c CROSS JOIN user u "
                 "WHERE c.id IN (%s) AND u.id IN (%s);" % (id_list, ','.join(str(item) for item in PARTICIPANTS)))
        submissions = []
        for index, contest_id in enumerate(ids):
            end = end_base + dt.timedelta(minutes=index)
            start = end - dt.timedelta(hours=2)
            for order, user_id in enumerate(PARTICIPANTS):
                submitted = start + dt.timedelta(minutes=7 + order * 5)
                stamp = submitted.strftime('%Y-%m-%d %H:%M:%S')
                submissions.append("(1,%d,'cpp',100,1,10,1024,'%s','%s',%d,'FINISHED')" % (user_id, stamp, stamp, contest_id))
        self.sql('INSERT INTO submission (problem_id,user_id,language,score,status,run_time,run_memory,'
                 'create_time,update_time,contest_id,judge_phase) VALUES ' + ','.join(submissions) + ';')
        return ids

    def finalize(self, contest_ids):
        for contest_id in contest_ids:
            status, payload = self.api('/contest/%d/rank/finalize' % contest_id, method='POST', token=self.admin_token)
            require(status == 200 and payload.get('code') == 200,
                    'finalize %d failed: %s %s' % (contest_id, status, payload))

    def snapshot(self, contest_ids):
        id_list = ','.join(str(item) for item in contest_ids)
        ranks = self.sql('SELECT * FROM contest_rank WHERE contest_id IN (%s) ORDER BY contest_id,user_id,id;' % id_list)
        history = self.sql('SELECT * FROM user_rating WHERE contest_id IN (%s) ORDER BY contest_id,user_id,id;' % id_list)
        settlements = self.sql('SELECT * FROM contest_settlement WHERE contest_id IN (%s) ORDER BY contest_id;' % id_list)
        ratings = self.sql('SELECT id,rating FROM user WHERE id IN (%s) ORDER BY id;' %
                           ','.join(str(item) for item in PARTICIPANTS))
        return {
            'ranks': ranks,
            'history': history,
            'settlements': settlements,
            'ratings': ratings,
            'rank_rows': count_rows(ranks),
            'history_rows': count_rows(history),
            'settlement_rows': count_rows(settlements),
            'digest': canonical([ranks, history, settlements, ratings]),
        }

    def reconcile(self, before, contest_ids):
        after = self.snapshot(contest_ids)
        equal = all(before[key] == after[key] for key in ('ranks', 'history', 'settlements', 'ratings'))
        return {
            'equal': equal,
            'before_digest': before['digest'],
            'after_digest': after['digest'],
            'rank_rows': after['rank_rows'],
            'history_rows': after['history_rows'],
            'settlement_rows': after['settlement_rows'],
        }

    def replay_summary(self, from_id):
        return self.sql("SELECT CONCAT(id,':',status,':',total_contests,':',affected_users,':',"
                        "IF(backup_json IS NULL,'no-backup','backup'),':',IFNULL(restored_at,'-')) "
                        "FROM contest_settlement_replay WHERE from_contest_id=%d ORDER BY id;" % from_id)

    def replay_rows(self, from_id=None):
        where = '' if from_id is None else ' WHERE from_contest_id=%d' % from_id
        return int(self.sql('SELECT COUNT(*) FROM contest_settlement_replay' + where + ';') or '0')

    def settlement_rows(self, contest_ids):
        return self.sql('SELECT contest_id,settlement_no,algorithm_version,rating_algorithm_version '
                        'FROM contest_settlement WHERE contest_id IN (%s) ORDER BY contest_id;' %
                        ','.join(str(item) for item in contest_ids))

    def settlement_numbers(self, contest_ids):
        rows = self.settlement_rows(contest_ids)
        numbers = {}
        for line in rows.splitlines():
            fields = line.split('\t')
            numbers[int(fields[0])] = int(fields[1])
        return numbers

    def audit_count(self, where):
        return int(self.sql('SELECT COUNT(*) FROM audit_log WHERE ' + where + ';') or '0')

    def audit_wait(self, where, timeout=25):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.audit_count(where) > 0:
                return True
            time.sleep(0.5)
        return False

    def audit_rows(self, where, limit=5):
        return self.sql('SELECT action,status,LEFT(IFNULL(error_message,""),200) FROM audit_log WHERE '
                        + where + ' ORDER BY id DESC LIMIT %d;' % limit)

    # ------------------------------------------------------------------ config
    def config_evidence(self):
        application_yml = (ROOT / 'OJ-Backend/oj-admin/src/main/resources/application.yml').read_text().splitlines()
        prod_compose = (ROOT / 'docker-compose.prod.yml').read_text().splitlines()

        def locate(lines, needle, context=2):
            for index, line in enumerate(lines):
                if needle in line:
                    return {
                        'line': index + 1,
                        'text': line.strip(),
                        'context': [item.strip() for item in lines[max(0, index - context):index + context + 1]],
                    }
            return {'line': None, 'text': None, 'context': []}

        return {
            'application_yml_replay_switch': locate(application_yml, 'OJ_SETTLEMENT_REPLAY_ENABLED'),
            'prod_compose_replay_switch': locate(prod_compose, 'OJ_SETTLEMENT_REPLAY_ENABLED'),
        }

    # --------------------------------------------------------------- scenarios
    def scenario_config_off(self):
        seed_contest = 1
        data, preview_ms = self.preview_ok(seed_contest)
        warnings = data['warnings']
        require(any('链式重放维护开关未开启' in warning for warning in warnings),
                'switch-off warning missing: %s' % warnings)
        require(data['canExecute'] is False, 'switch-off preview must not be executable')
        rows_before = self.replay_rows()
        status, payload, execute_ms = self.execute(seed_contest, 'OPS-01 switch-off probe', data['confirmation'])
        require(payload.get('code') != 200, 'switch-off execute must fail: %s %s' % (status, payload))
        rows_after = self.replay_rows()
        require(rows_after == rows_before, 'switch-off execute must not create replay rows')
        log_line = self.backend_log_line('链式重放维护开关未开启')
        self.record('config_switch_off',
                    preview_warnings=warnings,
                    can_execute=data['canExecute'],
                    preview_ms=preview_ms,
                    execute_http_status=status,
                    execute_business_code=payload.get('code'),
                    execute_message=payload.get('message'),
                    execute_ms=execute_ms,
                    replay_rows_before=rows_before,
                    replay_rows_after=rows_after,
                    backend_log_line=log_line)
        self.metric(scenario='config', operation='preview(switch off)', duration_ms=preview_ms, contests=len(data['contests']))
        self.metric(scenario='config', operation='execute(switch off, refused)', duration_ms=execute_ms, contests=len(data['contests']))

    def scenario_m4(self):
        contest_ids = self.create_chain(910001, 3, dt.datetime(2026, 3, 1, 12, 0, 0), 'OPS-01 M4 chain contest')
        self.finalize(contest_ids)
        before = self.snapshot(contest_ids)
        data, preview_ms = self.preview_ok(contest_ids[0])
        require(data['canExecute'] is True, 'M4 preview must be executable: %s' % data['warnings'])
        require(len(data['contests']) == 3, 'M4 chain must contain 3 contests, got %d' % len(data['contests']))
        require(data['affectedUsers'] == len(PARTICIPANTS),
                'M4 affected users must be %d, got %d' % (len(PARTICIPANTS), data['affectedUsers']))
        require(data['totalOldRows'] == len(PARTICIPANTS) * 3,
                'M4 old rows must be %d, got %d' % (len(PARTICIPANTS) * 3, data['totalOldRows']))
        empty_status, empty_payload, _ = self.execute(contest_ids[0], '', data['confirmation'])
        require(empty_payload.get('code') == 400,
                'empty reason must be rejected with business code 400: %s %s' % (empty_status, empty_payload))
        require('必须填写 1~500 字的操作原因' in str(empty_payload.get('message')),
                'reason validation message mismatch: %s' % (empty_payload,))
        stale_status, stale_payload, _ = self.execute(contest_ids[0], 'OPS-01 stale confirmation probe',
                                                      'REPLAY:%d:deadbeef' % contest_ids[0])
        require(stale_payload.get('code') != 200, 'stale confirmation must be rejected: %s' % (stale_payload,))
        require(self.replay_rows(contest_ids[0]) == 0, 'rejected attempts must not persist replay rows')
        require(self.snapshot(contest_ids) == before, 'rejected attempts must not write any data')
        status, payload, execute_ms = self.execute(contest_ids[0], 'OPS-01 drill chain replay', data['confirmation'])
        require(status == 200 and payload.get('code') == 200, 'M4 execute failed: %s %s' % (status, payload))
        result = payload['data']
        replay_id = result['replayId']
        require(result['contestCount'] == 3 and result['affectedUsers'] == len(PARTICIPANTS),
                'M4 replay result mismatch: %s' % (result,))
        numbers = self.settlement_numbers(contest_ids)
        require(all(number == 2 for number in numbers.values()),
                'M4 replay must increment every settlement_no to 2: %s' % numbers)
        summary = self.replay_summary(contest_ids[0])
        require(':COMPLETED:' in summary and 'backup' in summary, 'M4 replay record incomplete: %s' % summary)
        require(self.snapshot(contest_ids)['ratings'] == before['ratings'],
                'M4 replay must be rating-idempotent')
        restore_status, restore_payload, restore_preview_ms = self.restore_preview(replay_id)
        require(restore_status == 200 and restore_payload.get('code') == 200,
                'M4 restore preview failed: %s %s' % (restore_status, restore_payload))
        restore_preview_data = restore_payload['data']
        require(restore_preview_data['canExecute'] is True,
                'M4 restore preview must be executable: %s' % restore_preview_data['warnings'])
        require(restore_preview_data['confirmation'].startswith('RESTORE:%d:' % replay_id),
                'restore confirmation prefix mismatch: %s' % restore_preview_data['confirmation'][:40])
        restore_status, restore_payload, restore_ms = self.restore(
            replay_id, 'OPS-01 drill chain restore', restore_preview_data['confirmation'])
        require(restore_status == 200 and restore_payload.get('code') == 200,
                'M4 restore failed: %s %s' % (restore_status, restore_payload))
        reconciled = self.reconcile(before, contest_ids)
        require(reconciled['equal'] is True, 'M4 restore must reproduce every original row: %s' % reconciled)
        summary_after_restore = self.replay_summary(contest_ids[0])
        require(':RESTORED:' in summary_after_restore,
                'M4 replay record must become RESTORED: %s' % summary_after_restore)
        second_status, second_payload, _ = self.restore_preview(replay_id)
        require(second_payload.get('code') != 200, 'restored record must not be restorable again: %s' % (second_payload,))
        second_log_line = self.backend_log_line('仅成功且未恢复的重放可恢复')
        history_status, history_payload = self.api('/admin/settlement-replay/history', token=self.admin_token)
        require(history_status == 200 and history_payload.get('code') == 200, 'history list failed')
        require(all(row.get('backupJson') is None for row in history_payload['data']),
                'history list must not expose backups')
        detail_status, detail_payload = self.api('/admin/settlement-replay/history/%d' % replay_id, token=self.admin_token)
        require(detail_status == 200 and detail_payload.get('code') == 200, 'replay detail failed')
        require(detail_payload['data'].get('backupJson'), 'admin detail must expose the v2 backup')
        require(self.audit_wait("action='replay_settlement_chain' AND status=1"), 'execute audit row missing')
        require(self.audit_wait("action='restore_settlement_replay' AND status=1"), 'restore audit row missing')
        require(self.audit_wait("action='read_replay_backup' AND status=1"), 'backup read audit row missing')
        self.record('m4_replay_restore',
                    replay_id=replay_id,
                    preview_ms=preview_ms,
                    preview_warnings=data['warnings'],
                    preview_confirmation_length=len(data['confirmation']),
                    execute_ms=execute_ms,
                    restore_preview_ms=restore_preview_ms,
                    restore_ms=restore_ms,
                    settlement_numbers_after_replay=numbers,
                    replay_record_after_replay=summary,
                    replay_record_after_restore=summary_after_restore,
                    reconciliation=reconciled,
                    replay_record=replay_id,
                    second_restore_rejected=second_payload.get('code') != 200,
                    second_restore_log_line=second_log_line)
        self.metric(scenario='M4', operation='preview(3 contests/18 rows)', duration_ms=preview_ms,
                    contests=3, affected_users=len(PARTICIPANTS), old_rows=data['totalOldRows'])
        self.metric(scenario='M4', operation='execute replay', duration_ms=execute_ms,
                    contests=3, affected_users=len(PARTICIPANTS), old_rows=data['totalOldRows'])
        self.metric(scenario='M4', operation='preview restore', duration_ms=restore_preview_ms, contests=3)
        self.metric(scenario='M4', operation='restore', duration_ms=restore_ms, contests=3)

    def scenario_m5a(self):
        contest_ids = self.create_chain(920001, 1, dt.datetime(2026, 3, 2, 12, 0, 0), 'OPS-01 M5a contest')
        self.finalize(contest_ids)
        data, preview_ms = self.preview_ok(contest_ids[0])
        status, payload, execute_ms = self.execute(contest_ids[0], 'OPS-01 M5a replay', data['confirmation'])
        require(payload.get('code') == 200, 'M5a replay failed: %s %s' % (status, payload))
        replay_id = payload['data']['replayId']
        self.sql("UPDATE contest_rank SET award='OPS-01 后续奖项修改' WHERE contest_id=%d AND user_id=%d;"
                 % (contest_ids[0], PARTICIPANTS[0]))
        after_write = self.snapshot(contest_ids)
        restore_status, restore_payload, restore_preview_ms = self.restore_preview(replay_id)
        require(restore_status == 200 and restore_payload.get('code') == 200,
                'M5a restore preview request failed: %s %s' % (restore_status, restore_payload))
        restore_preview_data = restore_payload['data']
        require(restore_preview_data['canExecute'] is False,
                'M5a restore preview must be blocked after a later write')
        require(any('重放后数据已变化' in warning for warning in restore_preview_data['warnings']),
                'M5a drift warning missing: %s' % restore_preview_data['warnings'])
        restore_status, restore_payload, restore_ms = self.restore(
            replay_id, 'OPS-01 M5a restore attempt', restore_preview_data['confirmation'])
        require(restore_payload.get('code') != 200, 'M5a restore must be refused after later writes')
        require(self.snapshot(contest_ids) == after_write, 'M5a refused restore must not write any data')
        log_line = self.backend_log_line('不能覆盖后续写入')
        self.record('m5a_post_write_refusal',
                    replay_id=replay_id,
                    preview_ms=preview_ms,
                    execute_ms=execute_ms,
                    restore_preview_ms=restore_preview_ms,
                    restore_ms=restore_ms,
                    restore_preview_warnings=restore_preview_data['warnings'],
                    can_execute=False,
                    restore_business_code=restore_payload.get('code'),
                    data_unchanged=True,
                    backend_log_line=log_line)
        self.metric(scenario='M5a', operation='execute replay', duration_ms=execute_ms, contests=1)
        self.metric(scenario='M5a', operation='restore refused', duration_ms=restore_ms, contests=1)

    def scenario_m5b(self):
        contest_ids = self.create_chain(930001, 2, dt.datetime(2026, 3, 3, 12, 0, 0), 'OPS-01 M5b contest')
        self.finalize(contest_ids)
        first_preview, first_preview_ms = self.preview_ok(contest_ids[0])
        status, payload, first_execute_ms = self.execute(contest_ids[0], 'OPS-01 M5b first replay',
                                                         first_preview['confirmation'])
        require(payload.get('code') == 200, 'M5b first replay failed: %s %s' % (status, payload))
        first_id = payload['data']['replayId']
        after_first = self.snapshot(contest_ids)
        second_preview, second_preview_ms = self.preview_ok(contest_ids[0])
        require(second_preview['canExecute'] is True, 'M5b second preview must be executable')
        status, payload, second_execute_ms = self.execute(contest_ids[0], 'OPS-01 M5b second replay',
                                                          second_preview['confirmation'])
        require(payload.get('code') == 200, 'M5b second replay failed: %s %s' % (status, payload))
        second_id = payload['data']['replayId']
        numbers = self.settlement_numbers(contest_ids)
        require(all(number == 3 for number in numbers.values()),
                'M5b two replays must increment settlement_no to 3: %s' % numbers)
        older_status, older_payload, _ = self.restore_preview(first_id)
        require(older_status == 200 and older_payload.get('code') == 200, 'M5b older restore preview request failed')
        older_preview = older_payload['data']
        require(older_preview['canExecute'] is False, 'M5b older replay must not be restorable')
        require(any('只能恢复最近一条' in warning for warning in older_preview['warnings']),
                'M5b latest-only warning missing: %s' % older_preview['warnings'])
        older_restore_status, older_restore_payload, _ = self.restore(
            first_id, 'OPS-01 M5b older restore attempt', older_preview['confirmation'])
        require(older_restore_payload.get('code') != 200, 'M5b older restore must be refused')
        latest_status, latest_payload, latest_preview_ms = self.restore_preview(second_id)
        require(latest_status == 200 and latest_payload.get('code') == 200, 'M5b latest restore preview failed')
        latest_preview = latest_payload['data']
        require(latest_preview['canExecute'] is True,
                'M5b latest restore preview must be executable: %s' % latest_preview['warnings'])
        status, payload, latest_restore_ms = self.restore(
            second_id, 'OPS-01 M5b latest restore', latest_preview['confirmation'])
        require(payload.get('code') == 200, 'M5b latest restore failed: %s %s' % (status, payload))
        reconciled = self.reconcile(after_first, contest_ids)
        require(reconciled['equal'] is True,
                'M5b restore of latest replay must reproduce the post-first-replay state: %s' % reconciled)
        latest_log_line = self.backend_log_line('只能恢复最近一条尚未恢复的成功重放')
        self.record('m5b_latest_only_refusal',
                    first_replay_id=first_id,
                    second_replay_id=second_id,
                    first_preview_ms=first_preview_ms,
                    second_preview_ms=second_preview_ms,
                    first_execute_ms=first_execute_ms,
                    second_execute_ms=second_execute_ms,
                    settlement_numbers_after_two_replays=numbers,
                    older_preview_warnings=older_preview['warnings'],
                    older_restore_business_code=older_restore_payload.get('code'),
                    latest_restore_preview_ms=latest_preview_ms,
                    latest_restore_ms=latest_restore_ms,
                    reconciliation=reconciled,
                    backend_log_line=latest_log_line)
        self.metric(scenario='M5b', operation='execute first replay', duration_ms=first_execute_ms, contests=2)
        self.metric(scenario='M5b', operation='execute second replay', duration_ms=second_execute_ms, contests=2)
        self.metric(scenario='M5b', operation='restore latest', duration_ms=latest_restore_ms, contests=2)

    def scenario_m2(self):
        contest_ids = self.create_chain(940001, 2, dt.datetime(2026, 3, 4, 12, 0, 0), 'OPS-01 M2 contest')
        self.finalize(contest_ids)
        self.m2_chain = contest_ids
        self.m2_before = self.snapshot(contest_ids)
        data, preview_ms = self.preview_ok(contest_ids[0])
        require(data['canExecute'] is True, 'M2 preview must be executable: %s' % data['warnings'])
        results = []

        def worker():
            began = time.monotonic()
            status, payload, duration = self.execute(contest_ids[0], 'OPS-01 M2 concurrent replay',
                                                     data['confirmation'])
            results.append({'begin': began, 'end': time.monotonic(), 'status': status,
                            'payload': payload, 'duration_ms': duration})

        threads = [threading.Thread(target=worker) for _ in range(2)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        require(len(results) == 2, 'M2 needs two concurrent results')
        successes = [item for item in results if item['payload'].get('code') == 200]
        failures = [item for item in results if item['payload'].get('code') != 200]
        require(len(successes) == 1 and len(failures) == 1,
                'exactly one concurrent execute may succeed: %s' % [(item['status'], item['payload'].get('code')) for item in results])
        completed = int(self.sql("SELECT COUNT(*) FROM contest_settlement_replay WHERE from_contest_id=%d "
                                 "AND status='COMPLETED';" % contest_ids[0]) or '0')
        require(completed == 1, 'exactly one COMPLETED replay row expected, got %d' % completed)
        numbers = self.settlement_numbers(contest_ids)
        require(all(number == 2 for number in numbers.values()),
                'concurrent replay must increment settlement_no exactly once: %s' % numbers)
        winner = successes[0]
        self.m2_replay_id = winner['payload']['data']['replayId']
        overlap = min(item['end'] for item in results) - max(item['begin'] for item in results)
        loser = failures[0]
        loser_log_line = self.backend_log_line('预览已变化或确认串不匹配', '预览已变化')
        self.record('m2_concurrent_execute',
                    preview_ms=preview_ms,
                    request_durations_ms=[item['duration_ms'] for item in results],
                    request_http_status=[item['status'] for item in results],
                    request_business_codes=[item['payload'].get('code') for item in results],
                    concurrent_overlap_ms=round(overlap * 1000, 1),
                    completed_rows=completed,
                    settlement_numbers=numbers,
                    winner_replay_id=self.m2_replay_id,
                    loser_business_code=loser['payload'].get('code'),
                    loser_message=loser['payload'].get('message'),
                    loser_backend_log_line=loser_log_line)
        self.metric(scenario='M2', operation='concurrent execute (winner)', duration_ms=winner['duration_ms'], contests=2)
        self.metric(scenario='M2', operation='concurrent execute (loser)', duration_ms=loser['duration_ms'], contests=2)

    def start_guard_lock_holder(self, hold_seconds):
        command = self.compose_command(['exec', '-T', 'mysql', 'sh', '-c',
                                        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B oj_db'])
        holder = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, env=self.base_env)
        script = ('BEGIN;\nSELECT id FROM contest_settlement_guard WHERE id=1 FOR UPDATE;\n'
                  'SELECT SLEEP(%s);\nCOMMIT;\n' % hold_seconds)
        holder.stdin.write(script.encode())
        holder.stdin.flush()
        return holder

    def measure_guard_lock(self, hold_seconds=2.5):
        command = self.compose_command(['exec', '-T', 'mysql', 'sh', '-c',
                                        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B oj_db'])
        holder = self.start_guard_lock_holder(hold_seconds)
        time.sleep(0.6)
        began = time.monotonic()
        waiter = subprocess.run(command, input=('SET SESSION innodb_lock_wait_timeout=20;\n'
                                                'START TRANSACTION;\n'
                                                'SELECT id FROM contest_settlement_guard WHERE id=1 FOR UPDATE;\n'
                                                'COMMIT;\n').encode(),
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                env=self.base_env, timeout=60)
        blocked_ms = round((time.monotonic() - began) * 1000, 1)
        holder.communicate(timeout=30)
        require(waiter.returncode == 0, 'lock waiter failed: %s' % waiter.stderr.decode(errors='replace')[-1000:])
        require(blocked_ms > 500, 'lock waiter was not blocked, measured %sms' % blocked_ms)
        return {'hold_seconds': hold_seconds, 'blocked_ms': blocked_ms,
                'blocked_during_hold': blocked_ms < hold_seconds * 1000 + 3000}

    def scenario_m3(self):
        lock = self.measure_guard_lock(2.5)
        variables = self.sql('SELECT @@innodb_lock_wait_timeout, @@max_connections, @@transaction_isolation;')
        self.record('m3_lock_wait_and_variables',
                    guard_lock_wait=lock,
                    mysql_variables={'innodb_lock_wait_timeout,max_connections,transaction_isolation': variables},
                    execute_durations_from_other_scenarios=[item for item in self.evidence['metrics'] if 'execute' in item['operation']])
        self.evidence['checks']['m3_lock_wait_and_variables']['transaction_durations_ms'] = [
            item for item in self.evidence['metrics'] if 'execute' in item['operation'] or 'restore' in item['operation']]

    def scenario_m6(self):
        require(self.m2_replay_id, 'M6 requires the M2 replay record')
        replay_id = self.m2_replay_id
        detail_status, detail_payload = self.api('/admin/settlement-replay/history/%d' % replay_id, token=self.admin_token)
        require(detail_status == 200 and detail_payload.get('code') == 200, 'M6 admin detail failed')
        require(detail_payload['data'].get('backupJson'), 'M6 admin must receive backup json')
        require(self.audit_wait("action='read_replay_backup' AND status=1"), 'M6 backup read not audited')
        student_status, student_payload = self.api('/admin/settlement-replay/history/%d' % replay_id,
                                                   token=self.student_token)
        require(student_status == 403 or student_payload.get('code') == 403,
                'M6 student backup read must be forbidden: %s %s' % (student_status, student_payload))
        student_audit = self.audit_count("action='read_replay_backup' AND user_id=%d" % int(self.student_id))
        require(student_audit == 0, 'M6 forbidden read must not be audited as a successful read')
        history_status, history_payload = self.api('/admin/settlement-replay/history', token=self.admin_token)
        require(history_status == 200 and history_payload.get('code') == 200, 'M6 history failed')
        require(all(row.get('backupJson') is None for row in history_payload['data']),
                'M6 history list must not expose backups')
        time.sleep(2.0)
        before = self.audit_count("method_name LIKE '%AdminSettlementReplayController%'")
        self.preview_ok(self.m2_chain[0])
        restore_preview_status, restore_preview_payload, restore_preview_ms = self.restore_preview(replay_id)
        require(restore_preview_status == 200 and restore_preview_payload.get('code') == 200,
                'M6 restore preview failed: %s %s' % (restore_preview_status, restore_preview_payload))
        restore_preview_data = restore_preview_payload['data']
        require(restore_preview_data['canExecute'] is True,
                'M6 restore preview should be executable before the stop-write window: %s'
                % restore_preview_data['warnings'])
        time.sleep(1.5)
        after = self.audit_count("method_name LIKE '%AdminSettlementReplayController%'")
        require(after == before, 'M6 preview endpoints should not be audited (gap G7), delta=%d' % (after - before))
        # 停写窗口：外部维护事务持有全局结算锁时，恢复请求必须等待锁释放而不是并行写入
        holder = self.start_guard_lock_holder(2.5)
        time.sleep(0.6)
        window = {}

        def restore_worker():
            began = time.monotonic()
            status, payload, duration = self.restore(
                replay_id, 'OPS-01 M6 stop-write window restore', restore_preview_data['confirmation'])
            window.update({'status': status, 'payload': payload,
                           'begin': began, 'end': time.monotonic(), 'duration_ms': duration})

        restore_thread = threading.Thread(target=restore_worker)
        restore_thread.start()
        time.sleep(0.3)
        began = time.monotonic()
        lock_probe = subprocess.run(self.compose_command(
            ['exec', '-T', 'mysql', 'sh', '-c',
             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B oj_db']),
            input=b'SET SESSION innodb_lock_wait_timeout=20;\nSTART TRANSACTION;\n'
                  b'SELECT id FROM contest_settlement_guard WHERE id=1 FOR UPDATE;\nCOMMIT;\n',
            env=self.base_env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
        blocked_ms = round((time.monotonic() - began) * 1000, 1)
        restore_thread.join(timeout=120)
        holder.communicate(timeout=30)
        require(not restore_thread.is_alive(), 'M6 restore thread did not finish')
        require(lock_probe.returncode == 0, 'M6 lock probe failed: %s'
                % lock_probe.stderr.decode(errors='replace')[-1000:])
        require(blocked_ms >= 1000, 'M6 lock probe must be blocked by the maintenance transaction, measured %sms' % blocked_ms)
        window_status, window_payload, window_ms = window['status'], window['payload'], window['duration_ms']
        require(window_status == 200 and window_payload.get('code') == 200,
                'M6 restore during stop-write window failed: %s %s' % (window_status, window_payload))
        require(window_ms >= 1200, 'M6 restore must wait for the guard lock during the stop-write window, measured %sms' % window_ms)
        window_reconciled = self.reconcile(self.m2_before, self.m2_chain)
        require(window_reconciled['equal'] is True,
                'M6 stop-write window restore must reconcile: %s' % window_reconciled)
        self.record('m6_backup_read_permission',
                    admin_read_http_status=detail_status,
                    admin_backup_present=True,
                    student_forbidden_http_status=student_status,
                    student_business_code=student_payload.get('code'),
                    student_audit_rows=student_audit,
                    backup_read_audit_rows=self.audit_count("action='read_replay_backup'"),
                    history_list_backup_null=True,
                    preview_audit_delta=after - before,
                    audit_rows=self.audit_rows("action='read_replay_backup'", 3),
                    restore_preview_ms=restore_preview_ms,
                    stop_write_window={
                        'guard_lock_hold_seconds': 2.5,
                        'external_lock_wait_ms': blocked_ms,
                        'restore_request_ms': window_ms,
                        'restore_business_code': window_payload.get('code'),
                        'restore_waited_for_guard_lock': window_ms >= 1200,
                        'reconciliation': window_reconciled,
                    })
        self.metric(scenario='M6', operation='restore under held guard lock', duration_ms=window_ms, contests=2)

    def scenario_m7(self):
        contest_ids = self.create_chain(970001, 2, dt.datetime(2026, 3, 5, 12, 0, 0), 'OPS-01 M7 contest')
        self.finalize(contest_ids)
        data, _ = self.preview_ok(contest_ids[0])
        require(data['canExecute'] is True, 'M7 preview must be executable: %s' % data['warnings'])
        before = self.snapshot(contest_ids)
        rows_before = self.replay_rows(contest_ids[0])
        self.sql('DROP TRIGGER IF EXISTS ops01_fail_rank_delete;')
        self.sql("CREATE TRIGGER ops01_fail_rank_delete BEFORE DELETE ON contest_rank FOR EACH ROW "
                 "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'OPS-01 injected failure';")
        try:
            status, payload, execute_ms = self.execute(contest_ids[0], 'OPS-01 M7 injection replay',
                                                       data['confirmation'])
        finally:
            self.sql('DROP TRIGGER IF EXISTS ops01_fail_rank_delete;')
        require(payload.get('code') != 200, 'M7 injected failure must fail the request: %s' % (payload,))
        rows_after = self.replay_rows(contest_ids[0])
        require(rows_after == rows_before, 'M7 failed transaction must not keep a replay row')
        require(self.snapshot(contest_ids) == before, 'M7 failed transaction must roll back every write')
        require(self.audit_wait("action='replay_settlement_chain' AND status=0"), 'M7 failed attempt not audited')
        failure_audit = self.audit_rows("action='replay_settlement_chain' AND status=0", 2)
        injection_log_line = self.backend_log_line('OPS-01 injected failure', 'ops01_fail_rank_delete')
        self.sql("INSERT INTO submission (problem_id,user_id,language,score,status,run_time,run_memory,"
                 "create_time,update_time,contest_id,judge_phase) VALUES "
                 "(1,4,'cpp',0,0,0,0,'2026-03-05 11:00:00','2026-03-05 11:00:00',%d,'QUEUED');" % contest_ids[0])
        pending_preview, pending_preview_ms = self.preview_ok(contest_ids[0])
        require(any('仍有待判提交' in warning for warning in pending_preview['warnings']),
                'M7 pending submission warning missing: %s' % pending_preview['warnings'])
        require(pending_preview['canExecute'] is False, 'M7 pending submission must block execution')
        pending_status, pending_payload, pending_ms = self.execute(contest_ids[0], 'OPS-01 M7 pending probe',
                                                                   pending_preview['confirmation'])
        require(pending_payload.get('code') != 200, 'M7 pending submission must reject execution')
        require(self.snapshot(contest_ids) == before, 'M7 pending-blocked attempt must not write')
        require(self.replay_rows(contest_ids[0]) == rows_before, 'M7 pending-blocked attempt must not persist rows')
        self.sql("DELETE FROM submission WHERE contest_id=%d AND status=0 AND judge_phase='QUEUED';" % contest_ids[0])
        pending_log_line = self.backend_log_line('仍有待判提交')
        self.record('m7_failure_rollback',
                    injected_failure_business_code=payload.get('code'),
                    injected_failure_ms=execute_ms,
                    replay_rows_before=rows_before,
                    replay_rows_after=rows_after,
                    data_rolled_back=True,
                    failure_audit_rows=failure_audit,
                    injection_log_line=injection_log_line,
                    pending_preview_warnings=pending_preview['warnings'],
                    pending_preview_ms=pending_preview_ms,
                    pending_execute_ms=pending_ms,
                    pending_business_code=pending_payload.get('code'),
                    pending_log_line=pending_log_line)
        self.metric(scenario='M7', operation='execute with injected mid-transaction failure', duration_ms=execute_ms, contests=2)
        self.metric(scenario='M7', operation='execute blocked by pending submission', duration_ms=pending_ms, contests=2)

    def insert_cap_contests(self, first_id, count, end_base, prefix):
        rows = []
        for index in range(count):
            contest_id = first_id + index
            end = end_base + dt.timedelta(minutes=index)
            start = end - dt.timedelta(hours=1)
            rows.append("(%d,'%s %d','%s','%s',60,'ACM','Public',2,'ops01-cap',0,1,0,0,'FORMAL',"
                        "'2026-02-01 00:00:00','2026-02-01 00:00:00')" % (
                            contest_id, prefix, index + 1,
                            start.strftime('%Y-%m-%d %H:%M:%S'), end.strftime('%Y-%m-%d %H:%M:%S')))
        self.sql('INSERT INTO contest (id,title,start_time,end_time,duration,mode,type,author_id,author_name,'
                 'participant_count,visible,is_deleted,plagiarism_checked,category,create_time,update_time) VALUES '
                 + ','.join(rows) + ';')
        self.sql('UPDATE contest SET rank_finalized_at = end_time WHERE id BETWEEN %d AND %d;'
                 % (first_id, first_id + count - 1))
        self.sql("INSERT INTO contest_settlement (contest_id,algorithm_version,rating_algorithm_version,"
                 "settlement_no,finalized_at,create_time,update_time) "
                 "SELECT id,'v1','elo-v1',1,end_time,NOW(),NOW() FROM contest WHERE id BETWEEN %d AND %d;"
                 % (first_id, first_id + count - 1))

    def insert_cap_ranks(self, contest_id, count, base_user_id):
        self.sql('SET SESSION cte_max_recursion_depth = %d;\n'
                 'INSERT INTO contest_rank (contest_id,user_id,rank_num,solved_count,penalty_time,total_score,'
                 'participant_type,algorithm_version,rating_before,rating_after,award,is_visible,is_deleted,'
                 'create_time,update_time,create_by,update_by) '
                 'WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < %d) '
                 "SELECT %d, %d + n, n, 1, n, 0, 'OFFICIAL', 'v1', 1200, 1200, NULL, 1, 0, NOW(), NOW(), 0, 0 FROM seq;"
                 % (count + 100, count, contest_id, base_user_id))

    def scenario_m1(self):
        cap_base = dt.datetime(2026, 3, 6, 12, 0, 0)
        self.insert_cap_contests(950001, 50, cap_base, 'OPS-01 M1 cap contest')
        preview_50, preview_50_ms = self.preview_ok(950001)
        require(len(preview_50['contests']) == 50,
                'M1 chain must contain exactly 50 contests, got %d' % len(preview_50['contests']))
        require(not any('链长度超过' in warning for warning in preview_50['warnings']),
                'M1 50-contest boundary must not be rejected: %s' % preview_50['warnings'])
        self.insert_cap_contests(950051, 1, cap_base + dt.timedelta(minutes=60), 'OPS-01 M1 cap contest')
        preview_51, preview_51_ms = self.preview_ok(950001)
        require(len(preview_51['contests']) == 51,
                'M1 chain must contain 51 contests, got %d' % len(preview_51['contests']))
        require(any('链长度超过 50' in warning for warning in preview_51['warnings']),
                'M1 >50 warning missing: %s' % preview_51['warnings'])
        require(preview_51['canExecute'] is False, 'M1 >50 chain must not be executable')
        rows_before = self.replay_rows(950001)
        chain_status, chain_payload, chain_ms = self.execute(950001, 'OPS-01 M1 over-limit probe',
                                                             preview_51['confirmation'])
        require(chain_payload.get('code') != 200, 'M1 over-limit execute must fail')
        require(self.replay_rows(950001) == rows_before, 'M1 over-limit execute must not create replay rows')
        chain_log_line = self.backend_log_line('链长度超过 50')
        row_base = dt.datetime(2026, 3, 7, 12, 0, 0)
        self.insert_cap_contests(960001, 1, row_base, 'OPS-01 M1 row contest')
        self.insert_cap_ranks(960001, 20000, 700000)
        preview_20000, preview_20000_ms = self.preview_ok(960001)
        require(preview_20000['totalOldRows'] == 20000,
                'M1 row boundary must report 20000 old rows, got %s' % preview_20000['totalOldRows'])
        require(not any('旧成绩行数超过 20000' in warning for warning in preview_20000['warnings']),
                'M1 20000-row boundary must not be rejected: %s' % preview_20000['warnings'])
        self.insert_cap_ranks(960001, 1, 700000 + 20000)
        preview_20001, preview_20001_ms = self.preview_ok(960001)
        require(preview_20001['totalOldRows'] == 20001,
                'M1 over-limit must report 20001 old rows, got %s' % preview_20001['totalOldRows'])
        require(any('旧成绩行数超过 20000' in warning for warning in preview_20001['warnings']),
                'M1 >20000-row warning missing: %s' % preview_20001['warnings'])
        require(preview_20001['canExecute'] is False, 'M1 >20000-row chain must not be executable')
        rows_before = self.replay_rows(960001)
        row_status, row_payload, row_ms = self.execute(960001, 'OPS-01 M1 row limit probe',
                                                       preview_20001['confirmation'])
        require(row_payload.get('code') != 200, 'M1 row-cap execute must fail')
        require(self.replay_rows(960001) == rows_before, 'M1 row-cap execute must not create replay rows')
        row_log_line = self.backend_log_line('旧成绩行数超过 20000')
        self.record('m1_protection_boundaries',
                    exactly_50_contests=len(preview_50['contests']),
                    preview_50_ms=preview_50_ms,
                    chain_50_warnings=preview_50['warnings'],
                    over_50_contests=len(preview_51['contests']),
                    preview_51_ms=preview_51_ms,
                    chain_51_warnings=preview_51['warnings'],
                    over_50_execute_business_code=chain_payload.get('code'),
                    over_50_execute_ms=chain_ms,
                    over_50_log_line=chain_log_line,
                    exactly_20000_rows=preview_20000['totalOldRows'],
                    preview_20000_ms=preview_20000_ms,
                    over_20000_rows=preview_20001['totalOldRows'],
                    preview_20001_ms=preview_20001_ms,
                    row_20001_warnings=preview_20001['warnings'],
                    over_20000_execute_business_code=row_payload.get('code'),
                    over_20000_execute_ms=row_ms,
                    over_20000_log_line=row_log_line)
        self.metric(scenario='M1', operation='preview 50 contests', duration_ms=preview_50_ms, contests=50)
        self.metric(scenario='M1', operation='preview 51 contests', duration_ms=preview_51_ms, contests=51)
        self.metric(scenario='M1', operation='preview 20000 rows', duration_ms=preview_20000_ms, old_rows=20000)
        self.metric(scenario='M1', operation='preview 20001 rows', duration_ms=preview_20001_ms, old_rows=20001)
        self.metric(scenario='M1', operation='execute refused over 50', duration_ms=chain_ms, contests=51)
        self.metric(scenario='M1', operation='execute refused over 20000', duration_ms=row_ms, old_rows=20001)

    # --------------------------------------------------------- offline self-check
    def offline_self_check(self):
        """No Docker / no network: verify inputs, config defaults and source contracts."""
        results = {}

        def check(name, condition, detail=None):
            results[name] = {'ok': bool(condition), 'detail': detail}

        jar_ok = self.jar.is_file()
        check('jar_present', jar_ok, {'path': str(self.jar),
                                      'bytes': self.jar.stat().st_size if jar_ok else None,
                                      'sha256': sha256_file(self.jar) if jar_ok else None})
        yml_text = (ROOT / 'OJ-Backend/oj-admin/src/main/resources/application.yml').read_text()
        compose_text = (ROOT / 'docker-compose.prod.yml').read_text()
        tests_compose = (ROOT / 'tests/compose.yml').read_text()
        service_text = (ROOT / 'OJ-Backend/oj-contest/src/main/java/com/musealgo/oj/contest/service/'
                        'ContestSettlementReplayService.java').read_text()
        controller_text = (ROOT / 'OJ-Backend/oj-contest/src/main/java/com/musealgo/oj/contest/controller/'
                           'AdminSettlementReplayController.java').read_text()
        migration = (ROOT / 'OJ-Backend/oj-admin/src/main/resources/db/migration/'
                     'V59__contest_settlement_replay.sql')
        default_off = '$' + '{OJ_SETTLEMENT_REPLAY_ENABLED:false}'
        prod_default_off = '$' + '{OJ_SETTLEMENT_REPLAY_ENABLED:-false}'
        check('replay_switch_default_off_application_yml',
              ('enabled: ' + default_off) in yml_text, {'needle': default_off})
        check('replay_switch_default_off_prod_compose', prod_default_off in compose_text)
        check('service_limits_50_and_20000',
              'MAX_CONTESTS = 50' in service_text and 'MAX_ROWS = 20000' in service_text)
        check('strict_greater_than_boundaries',
              'state.contests().size() > MAX_CONTESTS' in service_text
              and 'state.ranks().size() > MAX_ROWS' in service_text
              and 'newRows > MAX_ROWS' in service_text)
        check('only_latest_successful_replay_restorable',
              '只能恢复最近一条尚未恢复的成功重放' in service_text
              and '重放后数据已变化，不能覆盖后续写入' in service_text)
        check('transaction_rolls_back_everything',
              '@Transactional(rollbackFor = Exception.class)' in service_text)
        check('controller_admin_guard_and_audit',
              "hasRole('ADMIN')" in controller_text and 'read_replay_backup' in controller_text
              and 'restore_settlement_replay' in controller_text)
        check('replay_audit_table_migration_present', migration.is_file())
        check('compose_stack_has_mysql_and_redis',
              'image: mysql:8.0' in tests_compose and 'image: redis:6.0' in tests_compose
              and 'name:' not in tests_compose.splitlines()[0])
        check('scenario_catalog_complete',
              sorted(item['id'] for item in SCENARIOS) ==
              sorted(['config', 'M4', 'M5a', 'M5b', 'M2', 'M3', 'M6', 'M7', 'M1']))
        check('participants_exist_in_seed',
              all(('(%d,' % user_id) in (ROOT / 'OJ-Backend/db/seed.sql').read_text()
                  for user_id in PARTICIPANTS))
        self.evidence['self_check'] = {'ok': all(item['ok'] for item in results.values()),
                                       'checks': results,
                                       'jar_sha256': sha256_file(self.jar) if jar_ok else None}
        return self.evidence['self_check']['ok']

    # --------------------------------------------------------------------- run
    def run(self):
        self.evidence['plan'] = {'scenarios': SCENARIOS,
                                 'expected_minutes_total': sum(item['expected_minutes'] for item in SCENARIOS)}
        if self.args.self_check:
            ok = self.offline_self_check()
            path = self.output / 'replay-drill-selfcheck.json'
            path.write_text(json.dumps(self.evidence, ensure_ascii=False, indent=2))
            path.chmod(0o600)
            print(json.dumps({'ok': ok, 'evidence': str(path)}, ensure_ascii=False), flush=True)
            if not ok:
                raise SystemExit(1)
            return
        succeeded = False
        try:
            self.assert_environment()
            self.start_stack()
            self.evidence['config'] = self.config_evidence()
            proc = self.boot_backend(False)
            try:
                self.bootstrap_tokens()
                self.prepare_seed_settlements()
                self.scenario_config_off()
            finally:
                self.stop_backend(proc)
            proc = self.boot_backend(True)
            try:
                self.bootstrap_tokens()
                self.scenario_m4()
                self.scenario_m5a()
                self.scenario_m5b()
                self.scenario_m2()
                self.scenario_m3()
                self.scenario_m6()
                self.scenario_m7()
                self.scenario_m1()
            finally:
                self.stop_backend(proc)
            succeeded = True
        finally:
            try:
                self.cleanup()
            finally:
                self.evidence['ok'] = succeeded
                self.evidence['finished_at'] = now_iso()
                evidence_path = self.output / 'replay-drill.json'
                evidence_path.write_text(json.dumps(self.evidence, ensure_ascii=False, indent=2))
                evidence_path.chmod(0o600)
                print(json.dumps({'ok': succeeded, 'evidence': str(evidence_path),
                                  'metrics': len(self.evidence['metrics'])}, ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser(description='OPS-01 settlement replay maintenance drill')
    parser.add_argument('--output', default='/tmp/musealgo-replay-evidence',
                        help='private evidence directory (created 0700)')
    parser.add_argument('--jar', default=None,
                        help='oj-admin fat jar (default: OJ-Backend/oj-admin/target/oj-admin-0.1.0-SNAPSHOT.jar)')
    parser.add_argument('--self-check', action='store_true',
                        help='offline checks only: jar presence, config defaults, source contracts, scenario catalog')
    args = parser.parse_args()
    ReplayDrill(args).run()


if __name__ == '__main__':
    main()
