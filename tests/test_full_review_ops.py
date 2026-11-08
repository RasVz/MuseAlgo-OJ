"""Failure injection uses only local temporary files and fake service boundaries."""
import argparse
import contextlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result

class OperationFailureTests(unittest.TestCase):
    def test_dump_failure_and_failed_resume_preserve_both_reasons(self):
        ops = module('fr_ops', 'scripts/ops.py')
        args = argparse.Namespace(env_file='unused', compose='docker-compose.prod.yml', project='synthetic')
        service = ops.Operations(args)
        images = {'migrate': 'synthetic', 'backend': 'synthetic', 'frontend': 'synthetic'}
        initial = [{'Service': 'backend', 'State': 'running', 'Health': 'healthy'}]
        failed = [{'Service': 'backend', 'State': 'exited', 'Health': 'unhealthy'}]
        def compose(*parts, **kwargs):
            if parts[0] == 'exec':
                raise ValueError('synthetic dump failed')
        with tempfile.TemporaryDirectory() as tmp, patch.object(service, 'state', side_effect=[initial] + [failed] * 180), patch.object(service, 'active_image_ids', return_value=images), patch.object(service, 'compose', side_effect=compose), patch.object(service, 'ensure_stopped'), patch.object(ops.time, 'sleep'):
            with self.assertRaises(ValueError) as caught:
                service._backup_to(Path(tmp) / 'backup', True, images)
            self.assertIn('synthetic dump failed', str(caught.exception))
            self.assertNotIn('Backup saved', str(caught.exception))
            self.assertIn('恢复', str(caught.exception))
            self.assertIsNotNone(caught.exception.__cause__)

    def test_demo_check_and_sql_only_cannot_silently_do_nothing(self):
        result = subprocess.run([sys.executable, str(ROOT / 'scripts/gen-demo-testcases.py'), '--check', '--sql-only'], capture_output=True)
        self.assertNotEqual(0, result.returncode)

    def test_partial_account_creation_retains_private_recovery_credentials(self):
        synth = module('fr_synthetic', 'deploy/verify/synthetic/prepare_synthetic_data.py')
        with tempfile.TemporaryDirectory() as tmp, patch.object(synth, 'Api') as api:
            api.return_value.login.return_value = 'synthetic-token'
            api.return_value.call.side_effect = [None, RuntimeError('synthetic create failed')]
            with patch.object(sys, 'argv', ['prepare', '--admin-user', 'synthetic-admin', '--admin-pass', 'synthetic-password', '--evidence-dir', tmp]):
                self.assertEqual(1, synth.main())
            first = api.return_value.call.call_args_list[0].args[2]
            private = Path(tmp) / 'accounts.private.json'
            saved = json.loads(private.read_text())['accounts']
            self.assertIn(first['username'], saved)
            self.assertEqual(first['password'], saved[first['username']]['password'])
            self.assertEqual(0o600, private.stat().st_mode & 0o777)
            public = (Path(tmp) / 'summary.json').read_text()
            self.assertNotIn(first['password'], public)

class ReleaseSmokeFailureTests(unittest.TestCase):
    def run_smoke(self, restart_failure):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); binary = root / 'bin'; binary.mkdir(); volume = root / 'volume'; volume.mkdir()
            fake = binary / 'docker'
            fake.write_text('''#!/usr/bin/env python3
import os,sys,json,re
from pathlib import Path
args=sys.argv[1:]; command=' '.join(args)
with open(os.environ['FAKE_LOG'],'a') as log: log.write(json.dumps(args)+'\\n')
volume=Path(os.environ['FAKE_VOLUME'])
if args[:2]==['image','inspect']:
 print('musealgo' if '--format' in args else '{}')
elif args[:2]==['volume','ls']: print('\\n'.join('synthetic-volume-'+str(i) for i in range(5)))
elif args and args[0]=='run':
 if '_uploads-data:/d' in command:
  match=re.search(r'cp /src /d/([^ ;&]+)',command)
  if match:
   source=next(a.split(':/src:')[0] for a in args if ':/src:' in a);(volume/match[1]).write_text(Path(source).read_text())
  for name in re.findall(r'rm -f /d/([^ ;&]+)',command): (volume/name).unlink(missing_ok=True)
 elif 'SLIDEV_CLI' in command: print('SLIDEV_CLI_OK')
 elif 'CONF_OK' in command: print('CONF_OK\\n1\\nINDEX_OK')
 elif 'APPJAR_MODE' in command: print('APPJAR_MODE=444')
 else: print('uid=10001\\nAPPJAR_READONLY\\n'+'\\n'.join('DIR_OK '+d for d in ['/app/logs','/app/OJ-Coursewares','/app/OJ-Slidev','/data/testcases','/data/uploads']))
elif args and args[0]=='compose':
 if 'restart' in args: sys.exit(7 if os.environ['FAKE_FAIL_RESTART']=='1' else 0)
 elif 'ps' in args: print('backend Up')
 elif 'port' in args: print('127.0.0.1:8080' if 'backend' in args else '')
 elif 'exec' in args:
  match=re.search(r'cat /data/uploads/([^ ;]+)',command)
  if match and (volume/match[1]).exists(): print((volume/match[1]).read_text())
  else: print('oj-synthetic.json.log')
'''); fake.chmod(0o700)
            for name, content in {'sleep': '#!/bin/sh\nexit 0\n', 'date': '#!/bin/sh\necho 2026-11-08T00:00:00Z\n', 'curl': '''#!/bin/sh
case "$*" in
 *-sI*) printf 'X-Content-Type-Options: nosniff\\nX-Frame-Options: SAMEORIGIN\\nReferrer-Policy: same-origin\\n';;
 *-o*) case "$*" in *'/api/'*) printf 404;; *) printf 200;; esac;;
 *) printf '{"status":"UP"}';;
esac
'''}.items():
                path = binary / name; path.write_text(content); path.chmod(0o700)
            script = root / 'smoke.sh'
            script.write_text((ROOT / 'deploy/verify/smoke-release.sh').read_text().replace('/tmp/rel02-smoke.txt', str(root / 'old-probe.txt')))
            env = {k: v for k, v in os.environ.items() if k in ('PATH', 'HOME', 'LANG')}
            env.update(PATH=str(binary) + ':' + env['PATH'], TMPDIR=str(root), REL02_PROJECT='synthetic', REL02_BACKEND_IMAGE='synthetic-backend', REL02_FRONTEND_IMAGE='synthetic-frontend', REL02_ENV_FILE='unused', REL02_EVIDENCE_DIR=str(root/'evidence'), FAKE_VOLUME=str(volume), FAKE_LOG=str(root/'calls'), FAKE_FAIL_RESTART=str(int(restart_failure)))
            result = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True, timeout=15)
            calls = [json.loads(line) for line in (root/'calls').read_text().splitlines()]
            sources = [a.split(':/src:')[0] for call in calls for a in call if ':/src:' in a]
            return result, list(volume.iterdir()), [path for path in sources if Path(path).exists()]

    def test_failed_restart_cannot_pass_when_old_services_stay_healthy(self):
        result, _, _ = self.run_smoke(True)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn('重启命令失败', result.stdout)

    def test_probe_is_removed_from_volume_and_host_after_success(self):
        result, volume, sources = self.run_smoke(False)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual([], volume)
        self.assertEqual([], sources)

class IntegrationRunnerContractTests(unittest.TestCase):
    def test_disposable_runner_passes_the_guard_and_synthetic_broker_configuration(self):
        suite = module('fr_suite', 'tests/run_suite.py')
        class ReachedMaven(RuntimeError):
            pass
        captured = {}
        def run(command, **kwargs):
            if command[0] == './mvnw':
                captured.update(command=command, env=kwargs['env'].copy())
                raise ReachedMaven()
            return subprocess.CompletedProcess(command, 0, stdout=b'')
        previous = os.umask(0o077)
        try:
            with tempfile.TemporaryDirectory() as tmp, patch.object(suite, 'free_port', side_effect=range(25000, 25005)), patch.object(suite.subprocess, 'run', side_effect=run), patch.dict(os.environ, {'PATH': os.environ['PATH']}, clear=True):
                with self.assertRaises(ReachedMaven):
                    suite.run_suite(Path(tmp))
            command, env = captured['command'], captured['env']
            self.assertIn('-Dmusealgo.review.synthetic=true', command)
            self.assertIn('-Dmusealgo.review.expected-db-port=' + env['OJ_CI_MYSQL_PORT'], command)
            self.assertIn('127.0.0.1:' + env['OJ_CI_MYSQL_PORT'] + '/oj_db?', env['SPRING_DATASOURCE_URL'])
            self.assertEqual(env['OJ_CI_MQ_PORT'], env['OJ_RABBIT_PORT'])
            self.assertEqual(env['OJ_CI_MQ_PASSWORD'], env['OJ_RABBIT_PASS'])
            self.assertEqual('86400000', env['OJ_MESSAGING_OUTBOX_INITIAL_DELAY_MS'])
            # Cached Spring contexts must not each reserve the production pool.
            self.assertEqual('8', env['SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE'])
            self.assertEqual('0', env['SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE'])
            self.assertIn('-Dspring.test.context.cache.maxSize=4', command)
        finally:
            os.umask(previous)
