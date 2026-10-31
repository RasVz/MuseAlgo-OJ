"""Maintenance command behaviour with a stateful fake Docker boundary."""
import argparse
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('musealgo_ops_worker_test', REPO / 'scripts/ops.py')
OPS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(OPS)

BACKEND_IMAGE = 'sha256:' + 'a' * 64
FRONTEND_IMAGE = 'sha256:' + 'b' * 64


class FakeCompose:
    def __init__(self, worker='running'):
        self.services = {'frontend': 'running', 'backend': 'running'}
        self.configured = {'frontend', 'backend', 'mysql', 'redis'}
        if worker is not None:
            self.configured.add('report-worker')
        if worker not in (None, 'absent'):
            self.services['report-worker'] = worker
        self.snapshot_states = []
        self.calls = []
        self.database_version = 67
        self.migration_state = None
        self.worker_reference = 'synthetic-backend'
        self.fail_startup = False
        self.ignore_stop = False
        self.ignore_worker_stop = False
        self.ignore_stop_after_failed_startup = False

    def compose(self, *parts, **kwargs):
        self.calls.append(parts)
        if parts[:3] == ('config', '--format', 'json'):
            config = {'services': {'migrate': {'image': 'synthetic-backend'},
                                   'backend': {'image': 'synthetic-backend'},
                                   'frontend': {'image': 'synthetic-frontend'}}}
            if 'report-worker' in self.configured:
                config['services']['report-worker'] = {'image': self.worker_reference}
            return subprocess.CompletedProcess(parts, 0, stdout=json.dumps(config).encode())
        if parts[:3] == ('ps', '--format', 'json'):
            rows = [{'Service': name, 'State': state, 'Health': 'healthy', 'ID': name + '-id'}
                    for name, state in self.services.items()]
            return subprocess.CompletedProcess(parts, 0, stdout=json.dumps(rows).encode())
        if parts[0] == 'stop':
            for name in parts[3:]:
                if name not in self.configured:
                    raise subprocess.CalledProcessError(1, parts)
                if name in self.services and not self.ignore_stop and not (name == 'report-worker' and self.ignore_worker_stop):
                    self.services[name] = 'exited'
        elif parts[0] == 'start':
            for name in parts[1:]:
                self.services[name] = 'running'
        elif parts[0] == 'exec' and 'mysqldump' in str(parts):
            self.snapshot_states.append(dict(self.services))
            kwargs['stdout'].write(b'SELECT 1;\n')
        elif parts[0] == 'run' and 'tar' in parts:
            if hasattr(kwargs.get('stdout'), 'write'):
                self.snapshot_states.append(dict(self.services))
                with tarfile.open(fileobj=kwargs['stdout'], mode='w:gz'):
                    pass
        elif parts[0] == 'run' and 'sh' in parts:
            return subprocess.CompletedProcess(parts, 0, stdout=b'')
        elif parts[0] == 'run' and 'migrate' in parts and '--entrypoint' in parts:
            pending = [] if self.database_version == 68 else [{'version': '68', 'description': 'synthetic'}]
            status = {'current': str(self.database_version), 'target': '68', 'pending': pending}
            return subprocess.CompletedProcess(parts, 0,
                stdout=('MUSEALGO_DATABASE_STATUS=' + json.dumps(status) + '\n').encode())
        elif parts[0] == 'run' and 'migrate' in parts:
            self.migration_state = dict(self.services)
            self.database_version = 68
        elif parts[0] == 'up':
            for name in parts:
                if name in self.configured:
                    self.services[name] = 'running'
            if self.fail_startup and 'backend' in parts:
                if self.ignore_stop_after_failed_startup:
                    self.ignore_stop = True
                raise subprocess.CalledProcessError(1, parts)
        elif parts[0] == 'exec':
            query = kwargs.get('input', b'')
            return subprocess.CompletedProcess(parts, 0,
                stdout=b'68\n' if b'flyway_schema_history' in query else b'0\n')
        else:
            raise AssertionError('Unexpected fake compose command: ' + repr(parts))
        return subprocess.CompletedProcess(parts, 0, stdout=b'')

    @staticmethod
    def inspect(command, **kwargs):
        if command[:3] == ['docker', 'volume', 'ls']:
            return subprocess.CompletedProcess(command, 0, stdout=b'', stderr=b'')
        name = command[-1]
        value = (FRONTEND_IMAGE if name in ('synthetic-frontend', 'frontend-id') else
                 'sha256:' + 'c' * 64 if name == 'synthetic-foreign-worker' else BACKEND_IMAGE)
        return subprocess.CompletedProcess(command, 0, stdout=value + '\n', stderr='')


class BackupWorkerLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        args = argparse.Namespace(env_file=str(root / 'synthetic.env'),
                                  compose=str(REPO / 'docker-compose.prod.yml'),
                                  project='synthetic', output=str(root / 'backup'), backup_root=None)
        self.operations = OPS.Operations(args)
        self.fake = FakeCompose()
        self.root_patch = patch.object(OPS, 'ROOT', root / 'repository')
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)

    def backup(self):
        with patch.object(self.operations, 'compose', side_effect=self.fake.compose), \
             patch.object(OPS.subprocess, 'run', side_effect=self.fake.inspect), \
             contextlib.redirect_stdout(io.StringIO()) as output:
            self.operations.backup()
        return json.loads(output.getvalue())

    def test_backup_stops_running_worker_during_snapshot_and_resumes_it(self):
        result = self.backup()
        self.assertTrue(result['verified'])
        self.assertEqual(4, len(self.fake.snapshot_states))
        for state in self.fake.snapshot_states:
            self.assertEqual('exited', state['report-worker'])
        self.assertEqual('running', self.fake.services['report-worker'])

    def test_backup_leaves_previously_stopped_worker_stopped(self):
        self.fake = FakeCompose(worker='exited')
        self.assertTrue(self.backup()['verified'])
        self.assertTrue(all(state['report-worker'] == 'exited' for state in self.fake.snapshot_states))
        self.assertEqual('exited', self.fake.services['report-worker'])

    def test_backup_supports_compose_configuration_without_worker(self):
        self.fake = FakeCompose(worker=None)
        self.assertTrue(self.backup()['verified'])
        self.assertEqual(4, len(self.fake.snapshot_states))
        self.assertNotIn('report-worker', self.fake.services)

    def test_backup_refuses_worker_image_different_from_backend_before_stopping(self):
        self.fake.worker_reference = 'synthetic-foreign-worker'
        with self.assertRaisesRegex(ValueError, 'report-worker.*不同镜像'):
            self.backup()
        self.assertEqual('running', self.fake.services['report-worker'])
        self.assertEqual([], self.fake.snapshot_states)

    def test_backup_refuses_snapshot_if_worker_remains_running_after_stop(self):
        self.fake.ignore_stop = True
        with self.assertRaisesRegex(ValueError, '停写|未停止'):
            self.backup()
        self.assertEqual([], self.fake.snapshot_states)

    def test_backup_refuses_snapshot_if_worker_is_still_restarting(self):
        self.fake = FakeCompose(worker='restarting')
        self.fake.ignore_worker_stop = True
        with self.assertRaisesRegex(ValueError, '停写|未停止'):
            self.backup()
        self.assertEqual([], self.fake.snapshot_states)

    def test_upgrade_keeps_worker_stopped_through_migration_and_starts_it_afterwards(self):
        self.operations.args.apply = True
        self.operations.args.backup_root = self.temporary.name
        with patch.object(self.operations, 'compose', side_effect=self.fake.compose), \
             patch.object(OPS.subprocess, 'run', side_effect=self.fake.inspect), \
             contextlib.redirect_stdout(io.StringIO()) as output, \
             contextlib.redirect_stderr(io.StringIO()):
            self.operations.upgrade()
        self.assertTrue(json.loads(output.getvalue())['ok'])
        self.assertTrue(all(state['report-worker'] == 'exited' for state in self.fake.snapshot_states))
        self.assertEqual('exited', self.fake.migration_state['report-worker'])
        self.assertEqual('running', self.fake.services['report-worker'])

    def test_upgrade_starts_configured_worker_missing_from_old_deployment(self):
        self.fake = FakeCompose(worker='absent')
        self.operations.args.apply = True
        self.operations.args.backup_root = self.temporary.name
        with patch.object(self.operations, 'compose', side_effect=self.fake.compose), \
             patch.object(OPS.subprocess, 'run', side_effect=self.fake.inspect), \
             contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.operations.upgrade()
        self.assertNotIn('report-worker', self.fake.migration_state)
        self.assertEqual('running', self.fake.services['report-worker'])

    def test_failed_upgrade_stops_partially_started_worker(self):
        self.fake.fail_startup = True
        self.operations.args.apply = True
        self.operations.args.backup_root = self.temporary.name
        with patch.object(self.operations, 'compose', side_effect=self.fake.compose), \
             patch.object(OPS.subprocess, 'run', side_effect=self.fake.inspect), \
             contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaisesRegex(ValueError, '启动与健康检查'):
                self.operations.upgrade()
        self.assertEqual('exited', self.fake.services['report-worker'])

    def test_failed_upgrade_does_not_claim_stopped_when_worker_still_runs(self):
        self.fake.fail_startup = True
        self.fake.ignore_stop_after_failed_startup = True
        self.operations.args.apply = True
        self.operations.args.backup_root = self.temporary.name
        with patch.object(self.operations, 'compose', side_effect=self.fake.compose), \
             patch.object(OPS.subprocess, 'run', side_effect=self.fake.inspect), \
             contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaisesRegex(ValueError, '不能确认写入已停止'):
                self.operations.upgrade()
        self.assertEqual('running', self.fake.services['report-worker'])

    def test_upgrade_never_claims_stopped_with_unconfigured_running_worker(self):
        self.fake.configured.remove('report-worker')
        self.operations.args.apply = True
        self.operations.args.backup_root = self.temporary.name
        with patch.object(self.operations, 'compose', side_effect=self.fake.compose), \
             patch.object(OPS.subprocess, 'run', side_effect=self.fake.inspect), \
             contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaisesRegex(ValueError, '不能确认写入已停止'):
                self.operations.upgrade()
        self.assertEqual('running', self.fake.services['report-worker'])
        self.assertEqual([], self.fake.snapshot_states)

    def test_restore_starts_configured_worker_only_after_data_and_migration(self):
        self.backup()
        self.fake = FakeCompose(worker='absent')
        self.fake.services = {}
        self.operations.args.source = self.operations.args.output
        self.operations.args.confirm_empty_target = True
        self.operations.args.skip_migrations = False
        with patch.object(self.operations, 'compose', side_effect=self.fake.compose), \
             patch.object(OPS.subprocess, 'run', side_effect=self.fake.inspect), \
             contextlib.redirect_stdout(io.StringIO()) as output:
            self.operations.restore()
        self.assertTrue(json.loads(output.getvalue())['checksums_verified'])
        self.assertNotIn('report-worker', self.fake.migration_state)
        self.assertEqual('running', self.fake.services['report-worker'])

    def test_skip_migrations_refuses_running_worker_without_recorded_image(self):
        self.backup()
        manifest_file = Path(self.operations.args.output) / 'manifest.json'
        manifest = json.loads(manifest_file.read_text())
        manifest['images'].pop('report-worker')
        manifest_file.write_text(json.dumps(manifest))
        self.fake = FakeCompose(worker='absent')
        self.fake.services = {}
        self.operations.args.source = self.operations.args.output
        self.operations.args.confirm_empty_target = True
        self.operations.args.skip_migrations = True
        with patch.object(self.operations, 'compose', side_effect=self.fake.compose), \
             patch.object(OPS.subprocess, 'run', side_effect=self.fake.inspect), \
             contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(ValueError, '报告worker.*镜像'):
                self.operations.restore()
        self.assertFalse(any(call[0] == 'up' for call in self.fake.calls))

    def test_skip_migrations_preserves_legacy_backup_without_a_worker(self):
        self.backup()
        manifest_file = Path(self.operations.args.output) / 'manifest.json'
        manifest = json.loads(manifest_file.read_text())
        manifest['images'].pop('report-worker')
        manifest.pop('running_services')
        manifest_file.write_text(json.dumps(manifest))
        self.fake = FakeCompose(worker='absent')
        self.fake.services = {}
        self.operations.args.source = self.operations.args.output
        self.operations.args.confirm_empty_target = True
        self.operations.args.skip_migrations = True
        with patch.object(self.operations, 'compose', side_effect=self.fake.compose), \
             patch.object(OPS.subprocess, 'run', side_effect=self.fake.inspect), \
             contextlib.redirect_stdout(io.StringIO()):
            self.operations.restore()
        self.assertIsNone(self.fake.migration_state)
        self.assertNotIn('report-worker', self.fake.services)

    def test_skip_migrations_preserves_older_backup_without_image_metadata(self):
        self.backup()
        manifest_file = Path(self.operations.args.output) / 'manifest.json'
        manifest = json.loads(manifest_file.read_text())
        manifest.pop('images')
        manifest.pop('running_services')
        manifest_file.write_text(json.dumps(manifest))
        self.fake = FakeCompose(worker='absent')
        self.fake.services = {}
        self.operations.args.source = self.operations.args.output
        self.operations.args.confirm_empty_target = True
        self.operations.args.skip_migrations = True
        with patch.object(self.operations, 'compose', side_effect=self.fake.compose), \
             patch.object(OPS.subprocess, 'run', side_effect=self.fake.inspect), \
             contextlib.redirect_stdout(io.StringIO()):
            self.operations.restore()
        self.assertIsNone(self.fake.migration_state)
        self.assertNotIn('report-worker', self.fake.services)
        self.assertEqual('running', self.fake.services['backend'])
        self.assertEqual('running', self.fake.services['frontend'])

    def test_skip_migrations_refuses_running_worker_when_all_image_metadata_is_missing(self):
        self.backup()
        manifest_file = Path(self.operations.args.output) / 'manifest.json'
        manifest = json.loads(manifest_file.read_text())
        manifest.pop('images')
        manifest_file.write_text(json.dumps(manifest))
        self.fake = FakeCompose(worker='absent')
        self.fake.services = {}
        self.operations.args.source = self.operations.args.output
        self.operations.args.confirm_empty_target = True
        self.operations.args.skip_migrations = True
        with patch.object(self.operations, 'compose', side_effect=self.fake.compose), \
             patch.object(OPS.subprocess, 'run', side_effect=self.fake.inspect), \
             contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(ValueError, '报告worker.*镜像'):
                self.operations.restore()
        self.assertFalse(any(call[0] == 'up' for call in self.fake.calls))

    def test_skip_migrations_restores_previously_running_worker_with_its_recorded_image(self):
        self.backup()
        self.fake = FakeCompose(worker='absent')
        self.fake.services = {}
        self.operations.args.source = self.operations.args.output
        self.operations.args.confirm_empty_target = True
        self.operations.args.skip_migrations = True
        with patch.object(self.operations, 'compose', side_effect=self.fake.compose), \
             patch.object(OPS.subprocess, 'run', side_effect=self.fake.inspect), \
             contextlib.redirect_stdout(io.StringIO()):
            self.operations.restore()
        self.assertIsNone(self.fake.migration_state)
        self.assertEqual('running', self.fake.services['report-worker'])

    def test_restore_refuses_target_with_running_report_worker(self):
        self.backup()
        self.fake = FakeCompose()
        self.fake.services = {'report-worker': 'running'}
        self.operations.args.source = self.operations.args.output
        self.operations.args.confirm_empty_target = True
        self.operations.args.skip_migrations = False
        with patch.object(self.operations, 'compose', side_effect=self.fake.compose), \
             patch.object(OPS.subprocess, 'run', side_effect=self.fake.inspect), \
             contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(ValueError, 'running application'):
                self.operations.restore()
        self.assertFalse(any(call[0] == 'up' for call in self.fake.calls))

    def test_failed_restore_stops_partially_started_worker(self):
        self.backup()
        self.fake = FakeCompose(worker='absent')
        self.fake.services = {}
        self.fake.fail_startup = True
        self.operations.args.source = self.operations.args.output
        self.operations.args.confirm_empty_target = True
        self.operations.args.skip_migrations = False
        with patch.object(self.operations, 'compose', side_effect=self.fake.compose), \
             patch.object(OPS.subprocess, 'run', side_effect=self.fake.inspect), \
             contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(ValueError, 'application startup and health check'):
                self.operations.restore()
        self.assertEqual('exited', self.fake.services['report-worker'])

    def test_health_check_reports_configured_worker_is_not_running(self):
        self.backup()
        self.fake.services.update(mysql='running', redis='running', **{'report-worker': 'exited'})
        self.operations.args.backup_root = self.temporary.name
        self.operations.args.max_backup_age_hours = 26
        with patch.object(self.operations, 'compose', side_effect=self.fake.compose), \
             contextlib.redirect_stdout(io.StringIO()) as output:
            with self.assertRaises(SystemExit) as raised:
                self.operations.check()
        self.assertEqual(1, raised.exception.code)
        self.assertIn('report-worker is not ready', json.loads(output.getvalue())['issues'])


if __name__ == '__main__':
    unittest.main()
