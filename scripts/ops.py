#!/usr/bin/env python3
"""Compose operations: consistent backup, empty-target restore, and local alert probes.
Requires Python 3 + Docker Compose. Credentials remain in the selected env file/container.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
VOLUMES = {'testcases': '/data/testcases', 'uploads': '/data/uploads', 'coursewares': '/app/OJ-Coursewares'}


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''):
            digest.update(block)
    return digest.hexdigest()


class Operations:
    def __init__(self, args):
        self.args = args
        compose_path = Path(args.compose).resolve()
        # The explicitly selected env file is authoritative, even in a configured developer shell.
        self.env = os.environ.copy()
        for name in re.findall(r'\$\{([A-Za-z_][A-Za-z0-9_]*)', compose_path.read_text()):
            self.env.pop(name, None)
        self.cmd = ['docker', 'compose', '--env-file', str(Path(args.env_file).resolve()),
                    '-p', args.project, '-f', str(compose_path)]

    def compose(self, *parts, **kwargs):
        return subprocess.run(self.cmd+list(parts), env=self.env, check=True, **kwargs)

    def sql(self, query=None, **kwargs):
        command = ['exec', '-T', 'mysql', 'sh', '-c',
                   'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names oj_db']
        if query is not None:
            return self.compose(*command, input=query.encode(), stdout=subprocess.PIPE).stdout.decode().strip()
        return self.compose(*command, **kwargs)

    def state(self):
        raw = self.compose('ps', '--format', 'json', stdout=subprocess.PIPE).stdout.decode().strip()
        if not raw:
            return []
        parsed = json.loads(raw) if raw.startswith('[') else [json.loads(line) for line in raw.splitlines()]
        return parsed

    def volume(self, label, *command, **kwargs):
        return self.compose('run', '--rm', '--no-deps', '-T', '--entrypoint', 'tar', 'backend', *command, **kwargs)

    def backup(self):
        destination = Path(self.args.output).resolve()
        destination.mkdir(mode=0o700, parents=True, exist_ok=False)
        state = self.state()
        if any(item['Service']=='migrate' and item.get('State')=='running' for item in state):
            raise ValueError('Refusing backup while a migration is running')
        running = [item['Service'] for item in state
                   if item['Service'] in ('backend', 'frontend') and item.get('State') == 'running']
        try:
            # All application writers stop before the SQL snapshot and filesystem archives.
            self.compose('stop', '--timeout', '60', 'frontend', 'backend')
            with (destination/'database.sql').open('wb') as target:
                self.compose('exec', '-T', 'mysql', 'sh', '-c',
                             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction '
                             '--no-tablespaces --set-gtid-purged=OFF --routines --triggers --hex-blob oj_db', stdout=target)
            for label, directory in VOLUMES.items():
                with (destination/(label+'.tar.gz')).open('wb') as target:
                    self.volume(label, '-C', directory, '-czf', '-', '.', stdout=target)
            images = self.compose('images', '--format', 'json', stdout=subprocess.PIPE).stdout.decode()
            manifest = {'format': 1, 'created_at': dt.datetime.now(dt.timezone.utc).isoformat(),
                        'images': json.loads(images) if images.strip().startswith('[') else [json.loads(line) for line in images.splitlines() if line.strip()],
                        'files': {p.name: sha256(p) for p in sorted(destination.iterdir()) if p.is_file()}}
            (destination/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
            for path in destination.iterdir():
                path.chmod(0o600)
            print(json.dumps({'backup': str(destination), 'files_verified': len(manifest['files'])}))
        finally:
            if running:
                self.compose('start', *running)
                for _ in range(180):
                    state = {item['Service']: item for item in self.state()}
                    if all(state.get(name, {}).get('State')=='running' and
                           state[name].get('Health') in ('',None,'healthy') for name in running):
                        break
                    time.sleep(1)
                else:
                    raise ValueError('Backup saved, but original services did not become ready after restart')

    def verify(self, source):
        manifest = json.loads((source/'manifest.json').read_text())
        expected = {'database.sql', *(label+'.tar.gz' for label in VOLUMES)}
        if manifest.get('format') != 1 or set(manifest['files']) != expected:
            raise ValueError('Unsupported or incomplete backup manifest')
        for name, digest in manifest['files'].items():
            if sha256(source/name) != digest:
                raise ValueError('Backup checksum mismatch: '+name)
            if name.endswith('.tar.gz'):
                with tarfile.open(source/name) as archive:
                    for member in archive.getmembers():
                        path = Path(member.name)
                        if path.is_absolute() or '..' in path.parts or member.issym() or member.islnk() or member.isdev():
                            raise ValueError('Unsupported archive member: '+member.name)
        return manifest

    def restore(self):
        source = Path(self.args.source).resolve()
        self.verify(source)  # Validate before changing any target resources.
        if not self.args.confirm_empty_target:
            raise ValueError('Restore requires --confirm-empty-target and a NEW Compose project')
        # A running or populated target is never overwritten by this helper.
        if any(item.get('State') == 'running' and item['Service'] in ('backend','frontend') for item in self.state()):
            raise ValueError('Refusing restore into a running application')
        volumes = subprocess.run(['docker','volume','ls','--filter',
                                  'label=com.docker.compose.project='+self.args.project,'-q'],
                                 check=True,stdout=subprocess.PIPE).stdout
        if volumes.strip():
            raise ValueError('Refusing restore: target project already has volumes; use a NEW project name')
        self.compose('up', '-d', '--wait', 'mysql', 'redis')
        if self.sql('SELECT COUNT(*) FROM user;') != '0':
            raise ValueError('Refusing restore: target database already has users')
        for label, directory in VOLUMES.items():
            probe = self.compose('run', '--rm', '--no-deps', '-T', '--entrypoint', 'sh', 'backend', '-c',
                                 'find "$1" -mindepth 1 -print -quit', 'sh', directory, stdout=subprocess.PIPE)
            if probe.stdout.strip():
                raise ValueError('Refusing restore: target '+label+' volume is not empty')
        with (source/'database.sql').open('rb') as stream:
            self.sql(stdin=stream)
        for label, directory in VOLUMES.items():
            with (source/(label+'.tar.gz')).open('rb') as stream:
                self.volume(label, '-C', directory, '-xzf', '-', stdin=stream)
        if not self.args.skip_migrations:
            self.compose('run', '--rm', 'migrate')
        self.compose('up', '-d', '--wait', '--no-deps', 'backend', 'frontend')
        print(json.dumps({'restored': str(source), 'project': self.args.project, 'checksums_verified': True}))

    def check(self):
        issues = []
        state = self.state()
        services = {item['Service']: item for item in state}
        for name in ('mysql','redis','backend','frontend'):
            item = services.get(name, {})
            if item.get('State') != 'running' or item.get('Health') not in ('', 'healthy', None):
                issues.append(name+' is not ready')
        stalled = int(self.sql("SELECT COUNT(*) FROM judge_task WHERE status='PROCESSING' AND locked_at < NOW()-INTERVAL 10 MINUTE;"))
        failed = int(self.sql("SELECT COUNT(*) FROM judge_task WHERE status='FAILED' AND updated_at > NOW()-INTERVAL 1 HOUR;"))
        unsettled = int(self.sql("SELECT COUNT(*) FROM contest WHERE is_deleted=0 AND rank_finalized_at IS NULL AND end_time < NOW()-INTERVAL 15 MINUTE;"))
        failed_events = int(self.sql("SELECT COUNT(*) FROM outbox_event WHERE status='FAILED' AND created_at > NOW()-INTERVAL 1 HOUR;"))
        if failed_events:
            issues.append(f'{failed_events} failed outbox events in the last hour')
        if stalled:
            issues.append(f'{stalled} stalled judge tasks')
        if failed:
            issues.append(f'{failed} failed judge tasks in the last hour')
        if unsettled:
            issues.append(f'{unsettled} unsettled contests older than 15 minutes')
        if not self.args.backup_root:
            issues.append('backup freshness was not configured (--backup-root)')
        else:
            candidates = list(Path(self.args.backup_root).glob('*/manifest.json'))
            if not candidates:
                issues.append('no backup manifest found')
            else:
                latest = max(candidates, key=lambda p:p.stat().st_mtime)
                try:
                    manifest = self.verify(latest.parent)
                    age = (dt.datetime.now(dt.timezone.utc)-dt.datetime.fromisoformat(manifest['created_at'])).total_seconds()
                    if age > self.args.max_backup_age_hours*3600:
                        issues.append('backup is too old')
                except (ValueError, OSError):
                    issues.append('latest backup is corrupt')
        print(json.dumps({'ok': not issues, 'issues': issues, 'stalled_judges': stalled,
                          'failed_judges_last_hour': failed, 'failed_events_last_hour': failed_events, 'unsettled_contests': unsettled}, ensure_ascii=False))
        if issues:
            raise SystemExit(1)


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compose', default=str(ROOT/'docker-compose.prod.yml'))
    parser.add_argument('--project', default='musealgo-prod')
    parser.add_argument('--env-file', default=str(ROOT/'.env'))
    sub = parser.add_subparsers(dest='operation', required=True)
    backup = sub.add_parser('backup');backup.add_argument('--output', required=True)
    restore = sub.add_parser('restore');restore.add_argument('--source', required=True)
    restore.add_argument('--confirm-empty-target', action='store_true')
    restore.add_argument('--skip-migrations', action='store_true',help='Restore a verified old image/schema pair without upgrading the restored schema')
    verify = sub.add_parser('verify');verify.add_argument('--source', required=True)
    check = sub.add_parser('check');check.add_argument('--backup-root')
    check.add_argument('--max-backup-age-hours', type=int, default=26)
    args = parser.parse_args()
    operations = Operations(args)
    if args.operation == 'verify':
        operations.verify(Path(args.source).resolve());print('Backup checksums and archive members verified')
    else:
        getattr(operations,args.operation)()

if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        # Never dump subprocess command environments or connection credentials.
        print('Operation failed: '+str(error)[:300],file=sys.stderr)
        raise SystemExit(1)
