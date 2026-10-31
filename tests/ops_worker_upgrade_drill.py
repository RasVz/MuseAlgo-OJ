#!/usr/bin/env python3
"""Exercise upgrade while a report worker is running, in a disposable Compose project."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return str(sock.getsockname()[1])


def main(args):
    os.umask(0o077)
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    output.chmod(0o700)
    compose_file = ROOT / 'docker-compose.prod.yml'
    env = os.environ.copy()
    for name in re.findall(r'\$\{([A-Za-z_][A-Za-z0-9_]*)', compose_file.read_text()):
        env.pop(name, None)
    project = 'musealgo-drill-activeupgrade-' + secrets.token_hex(4)
    with tempfile.TemporaryDirectory(prefix='musealgo-activeupgrade-') as temporary:
        config = Path(temporary) / (project + '.env')
        values = {'OJ_DB_ROOT_PASS': secrets.token_hex(32), 'OJ_DB_MIGRATION_PASS': secrets.token_hex(32),
                  'OJ_DB_PASS': secrets.token_hex(32), 'JWT_SECRET': secrets.token_hex(48),
                  'BACKEND_IMAGE': args.backend_image, 'FRONTEND_IMAGE': args.frontend_image,
                  'BACKEND_PORT': port(), 'WEB_PORT': port(), 'WEB_BIND': '127.0.0.1', 'JAVA_OPTS': '-Xmx512m'}
        config.write_text('\n'.join(f'{key}={value}' for key, value in values.items()) + '\n')
        config.chmod(0o600)
        base = ['docker', 'compose', '--env-file', str(config), '-p', project, '-f', str(compose_file)]

        def compose(*parts, **kwargs):
            kwargs.setdefault('stdout', subprocess.PIPE)
            kwargs.setdefault('stderr', subprocess.PIPE)
            return subprocess.run(base + list(parts), env=env, check=True, **kwargs)

        def sql(statement):
            return compose('exec', '-T', 'mysql', 'sh', '-c',
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B oj_db',
                input=statement.encode()).stdout.decode().strip()

        def states():
            raw = compose('ps', '--all', '--format', 'json').stdout.decode().strip()
            rows = json.loads(raw) if raw.startswith('[') else [json.loads(line) for line in raw.splitlines() if line.strip()]
            return {row['Service']: {'state': row.get('State'), 'health': row.get('Health')} for row in rows}

        def ops(*parts):
            command = ['python3', str(ROOT / 'scripts/ops.py'), '--project', project, '--env-file', str(config)]
            return subprocess.run(command + list(parts), cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

        try:
            print('Starting a disposable project with the report worker enabled.', flush=True)
            compose('up', '--pull', 'never', '-d', '--wait', 'mysql', 'redis', 'backend', 'frontend', 'report-worker')
            assert states()['report-worker']['state'] == 'running'
            assert sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;") == '68'
            assert sql("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='oj_db' "
                       "AND table_name='report_job' AND index_name='ix_report_job_retention';") == '3'

            # Recreate a V67 state in this synthetic database while the current worker is live.
            sql("ALTER TABLE report_job DROP INDEX ix_report_job_retention; "
                "DELETE FROM flyway_schema_history WHERE version='68' AND success=1;")
            before = states()
            assert before['report-worker']['state'] == 'running'
            assert sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;") == '67'
            success = ops('upgrade', '--apply', '--backup-root', str(output / 'success-backups'))
            assert success.returncode == 0, success.stderr.decode(errors='replace')
            upgraded = json.loads(success.stdout)
            assert upgraded['ok'] and upgraded['changed'] and upgraded['target'] == '68'
            manifest = json.loads((Path(upgraded['backup']) / 'manifest.json').read_text())
            assert 'report-worker' in manifest['running_services']
            after = states()
            assert after['report-worker']['state'] == 'running' and after['report-worker']['health'] == 'healthy'
            assert sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version='68' AND success=1;") == '1'
            assert sql("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='oj_db' "
                       "AND table_name='report_job' AND index_name='ix_report_job_retention';") == '3'
            print('Upgrade with a live worker succeeded; injecting a duplicate-index migration failure.', flush=True)

            # Keep the V68 index but remove its history row so its real migration fails.
            # This intentional inconsistency exists only in the disposable project.
            sql("DELETE FROM flyway_schema_history WHERE version='68' AND success=1;")
            failure_before = states()
            assert failure_before['report-worker']['state'] == 'running'
            failed = ops('upgrade', '--apply', '--backup-root', str(output / 'failure-backups'))
            failure_text = failed.stderr.decode(errors='replace')
            assert failed.returncode == 1 and '数据库升级' in failure_text and '已校验备份位置' in failure_text, failure_text
            failure_after = states()
            assert all(failure_after.get(name, {}).get('state') in ('exited', 'dead', 'created')
                       for name in ('backend', 'frontend', 'report-worker')), failure_after
            diagnostic = re.search(r'诊断日志：([^\s]+)', failure_text)
            assert diagnostic, failure_text
            diagnostic_text = Path(diagnostic.group(1)).read_text(errors='replace')
            assert 'ix_report_job_retention' in diagnostic_text, 'expected duplicate-index failure not found'
            evidence = {'project': project, 'success_before': before, 'success_after': after,
                        'success_backup_worker_recorded': True, 'success_target': upgraded['target'],
                        'failure_before': failure_before, 'failure_after': failure_after,
                        'failure_exit': failed.returncode, 'failure_phase': 'database migration',
                        'failure_backup_verified': True, 'failure_cause': 'duplicate V68 retention index',
                        'synthetic_setup': 'remove V68 history with or without its index in an isolated database'}
            (output / 'active-worker-upgrade.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
            print('Successful and failed upgrades both checked the report worker state.', flush=True)
        finally:
            # The generated project name and volumes belong only to this drill.
            compose('down', '-v', '--remove-orphans')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend-image', required=True)
    parser.add_argument('--frontend-image', required=True)
    parser.add_argument('--output', required=True)
    main(parser.parse_args())
