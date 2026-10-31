#!/usr/bin/env python3
"""数据库维护：status / upgrade / backup / restore（无需指定数据库版本）。
Requires Python 3 + Docker Compose. Credentials remain in the selected env file/container.
"""
import argparse
import datetime as dt
import contextlib
import fcntl
import tempfile
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
from contextlib import contextmanager

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
        kwargs.setdefault('stdout', subprocess.PIPE)
        kwargs.setdefault('stderr', subprocess.PIPE)
        try:
            return subprocess.run(self.cmd+list(parts), env=self.env, check=True, **kwargs)
        except subprocess.CalledProcessError as error:
            error.log_path = self.save_failure(error)
            raise

    def save_failure(self, error):
        directory = ROOT.parent/'outputs'/'operations'
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        directory.chmod(0o700)
        with tempfile.NamedTemporaryFile(dir=directory, prefix='failure-', suffix='.log', delete=False) as log:
            for value in (getattr(error, 'stdout', None), getattr(error, 'stderr', None)):
                if value: log.write(value.encode() if isinstance(value,str) else value)
            return log.name

    @contextlib.contextmanager
    def maintenance_lock(self):
        directory = ROOT.parent/'outputs'/'operations'
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        directory.chmod(0o700)
        name = hashlib.sha256(self.args.project.encode()).hexdigest()[:24]+'.lock'
        with (directory/name).open('a') as lock:
            os.fchmod(lock.fileno(),0o600)
            try: fcntl.flock(lock, fcntl.LOCK_EX|fcntl.LOCK_NB)
            except BlockingIOError: raise ValueError('同一项目已有维护操作，请等待其结束') from None
            try: yield
            finally: fcntl.flock(lock, fcntl.LOCK_UN)

    def migration_status(self):
        image_ids = self.resolve_image_ids()
        with self.use_image_ids(image_ids):
            return self._migration_status(image_ids)

    def _migration_status(self, image_ids):
        # 直接指定新入口；旧镜像会因缺类而失败，不会把status当作migrate执行。
        response = self.compose('run', '--pull', 'never', '--rm', '--no-deps', '--entrypoint', 'java', 'migrate',
                '-cp', '/app/app.jar', '-Dloader.main=com.musealgo.oj.DatabaseMaintenance',
                'org.springframework.boot.loader.launch.PropertiesLauncher', 'status')
        lines = response.stdout.decode().splitlines()
        for line in reversed(lines):
            if line.startswith('MUSEALGO_DATABASE_STATUS='):
                result = json.loads(line.split('=',1)[1])
                if (not isinstance(result,dict) or not isinstance(result.get('current'),str)
                        or not isinstance(result.get('target'),str)
                        or not isinstance(result.get('pending'),list)):
                    break
                result['image_ids'] = image_ids
                return result
        raise ValueError('维护镜像未返回有效状态，请使用支持数据库维护入口的新发行镜像')

    def resolve_image_ids(self):
        """Resolve mutable Compose tags once, then pin every operation to local image IDs."""
        try:
            services = self.configured_services()
            references = {name: services[name]['image'] for name in ('migrate', 'backend', 'frontend')}
            if 'report-worker' in services:
                references['report-worker'] = services['report-worker']['image']
        except (KeyError, TypeError) as error:
            raise ValueError('Compose配置未提供维护、后端或前端镜像身份') from error

        image_ids = {}
        for service, reference in references.items():
            try:
                result = subprocess.run(['docker', 'image', 'inspect', '--format={{.Id}}', reference],
                                        env=self.env, check=True, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, text=True)
            except subprocess.CalledProcessError as error:
                raise ValueError(f'{service}镜像 {reference} 未在本机存在；拒绝隐式拉取可变tag') from error
            image_id = result.stdout.strip()
            if not re.fullmatch(r'sha256:[0-9a-f]{64}', image_id):
                raise ValueError(f'{service}镜像没有有效的不可变本地ID')
            image_ids[service] = image_id
        if image_ids['migrate'] != image_ids['backend']:
            raise ValueError('migrate与backend解析到不同镜像，拒绝使用不匹配的迁移目标')
        if 'report-worker' in image_ids and image_ids['report-worker'] != image_ids['backend']:
            raise ValueError('report-worker与backend解析到不同镜像，拒绝使用不匹配的报告工作进程')
        return image_ids

    def configured_services(self):
        try:
            services = json.loads(self.compose('config', '--format', 'json').stdout.decode())['services']
            if not isinstance(services, dict):
                raise TypeError('services must be an object')
            return services
        except (KeyError, TypeError, json.JSONDecodeError) as error:
            raise ValueError('无法读取Compose服务配置') from error

    @contextmanager
    def use_image_ids(self, image_ids):
        """Pin all maintenance/runtime services even when a custom Compose file hardcodes tags."""
        variable_names = ('BACKEND_IMAGE', 'FRONTEND_IMAGE')
        previous = {name: self.env.get(name) for name in variable_names}
        previous_command = self.cmd
        directory = ROOT.parent/'outputs'/'operations'
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        directory.chmod(0o700)
        override = tempfile.NamedTemporaryFile(dir=directory, prefix='compose-pinned-',
                                               suffix='.json', mode='w', encoding='utf-8',
                                               delete=False)
        override_path = Path(override.name)
        try:
            self.env['BACKEND_IMAGE'] = image_ids['backend']
            self.env['FRONTEND_IMAGE'] = image_ids['frontend']
            override.write(json.dumps({'services': {
                name: {'image': image_id} for name, image_id in image_ids.items()
            }}))
            override.close()
            os.chmod(override_path,0o600)
            self.cmd = previous_command + ['-f', str(override_path)]
            yield
        finally:
            if not override.closed:
                override.close()
            self.cmd = previous_command
            for name, value in previous.items():
                if value is None:
                    self.env.pop(name, None)
                else:
                    self.env[name] = value
            override_path.unlink(missing_ok=True)

    def status(self):
        report = self.migration_status()
        report['ready'] = not report['pending']
        if getattr(self.args,'json',False): print(json.dumps(report,ensure_ascii=False))
        else:
            state = ('已匹配' if report['ready'] else f"待升级 {len(report['pending'])} 项")
            print(f"数据库：{state}；当前版本 {report['current']}，程序目标 {report['target']}。")
            print('固定镜像ID：'+json.dumps(report['image_ids'],ensure_ascii=False))
            if not report['ready']:
                print('执行 upgrade --apply 将重新预检、自动备份再升级。')
        return report

    def backup_destination(self):
        output = getattr(self.args,'output',None)
        if output: return Path(output).resolve()
        root = Path(getattr(self.args,'backup_root',None) or ROOT.parent/'outputs'/'backups').resolve()
        name = self.args.project+'-'+dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d-%H%M%S-%f')
        return root/name

    def upgrade(self):
        image_ids = self.resolve_image_ids()
        with self.use_image_ids(image_ids):
            before = self._migration_status(image_ids)
        if not before['pending']:
            print(json.dumps({'ok':True,'changed':False,'current':before['current'],
                'target':before['target'],'image_ids':image_ids,
                'message':'数据库已匹配当前程序，无需升级'},ensure_ascii=False)); return
        if not self.args.apply:
            print(json.dumps({'preview':True,'current':before['current'],'target':before['target'],
                'pending':before['pending'],'image_ids':image_ids,
                'message':'本次预览绑定上列镜像；执行 upgrade --apply 时将重新预检并明确显示本次绑定，然后暂停业务、自动备份并校验、升级和检查健康'},ensure_ascii=False)); return
        print(json.dumps({'apply_preflight':True,'current':before['current'],'target':before['target'],
            'pending':before['pending'],'image_ids':image_ids,
            'message':'本次执行已绑定上述不可变镜像身份和迁移目标'},ensure_ascii=False),file=sys.stderr,flush=True)
        destination = self.backup_destination()
        with self.use_image_ids(image_ids):
            current = self._migration_status(image_ids)
        if (current['current'], current['target'], current['pending']) != (
                before['current'], before['target'], before['pending']):
            raise ValueError('数据库状态与刚才的预检不一致；未停写，请重新运行upgrade预览')
        phase = '一致备份'
        backup_verified = False
        stop_error = None
        try:
            with self.use_image_ids(image_ids):
                # Same immutable identities remain in force through backup, migration and startup.
                self.backup_to(destination, resume=False, image_ids=image_ids)
                self.verify(destination)
                backup_verified = True
                phase = '数据库升级'
                self.compose('run','--pull','never','--rm','--no-deps','migrate')
                phase = '升级后校验'
                after = self._migration_status(image_ids)
                if after['pending'] or after['current'] != before['target']:
                    raise ValueError('升级后结构未匹配本次固定镜像的预检目标')
                phase = '启动与健康检查'
                services = ['backend', 'frontend'] + (['report-worker'] if 'report-worker' in image_ids else [])
                self.compose('up','--pull','never','-d','--wait','--no-deps',*services)
            print(json.dumps({'ok':True,'changed':True,'current':before['current'],'target':before['target'],
                'image_ids':image_ids,'backup':str(destination),
                'message':'升级完成，服务健康'},ensure_ascii=False))
        except (ValueError,OSError,subprocess.CalledProcessError) as error:
            # 不自动repair/降级/切回旧程序；保留数据现场与备份，明确下一步。
            try:
                with self.use_image_ids(image_ids):
                    services = ['frontend', 'backend'] + (['report-worker'] if 'report-worker' in image_ids else [])
                    self.compose('stop','--timeout','60',*services)
                    self.ensure_stopped(services)
            except (subprocess.CalledProcessError, OSError, ValueError) as stop_failure:
                stop_error = stop_failure
            detail = getattr(error,'log_path',None)
            backup_message = (f'已校验备份位置：{destination}。' if backup_verified
                              else f'本次备份未通过校验，残留目录（不可直接用于恢复）：{destination}。')
            image_message = '固定镜像身份：'+json.dumps(image_ids,ensure_ascii=False)+'。'
            service_message = ('已请求业务停写。' if stop_error is None
                               else '停止业务命令也失败，不能确认写入已停止；请立即检查Compose服务状态。')
            stop_log = getattr(stop_error,'log_path',None) if stop_error else None
            raise ValueError(f'升级停止在「{phase}」。{service_message}{backup_message}{image_message}'
                f'请先修复本阶段原因后重试；需要恢复时使用新项目，禁止重置数据卷。'
                +(f' 诊断日志：{detail}' if detail else '')
                +(f' 停写诊断日志：{stop_log}' if stop_log else '')) from error


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
        return self.compose('run', '--pull', 'never', '--rm', '--no-deps', '-T', '--entrypoint', 'tar', 'backend', *command, **kwargs)

    def ensure_stopped(self, services):
        # An orphan worker may still exist even if the selected configuration no longer declares it.
        watched = set(services) | {'report-worker'}
        still_running = sorted({item['Service'] for item in self.state()
                                if item['Service'] in watched and item.get('State') not in ('exited', 'dead', 'created')})
        if still_running:
            raise ValueError('业务服务未停止，不能确认停写：' + ', '.join(still_running))

    def active_image_ids(self, state, fallback_image_ids):
        """Record the exact images serving this project, even if its configured tag has moved."""
        result = {}
        for service in ('backend', 'frontend', *(['report-worker'] if 'report-worker' in fallback_image_ids else [])):
            running = [item for item in state
                       if item.get('Service') == service and item.get('State') == 'running']
            if not running:
                result[service] = fallback_image_ids[service]
                continue
            if len(running) != 1 or not running[0].get('ID'):
                raise ValueError(f'无法唯一识别运行中的{service}容器镜像；拒绝生成不完整备份清单')
            try:
                inspected = subprocess.run(['docker','inspect','--format={{.Image}}',running[0]['ID']],
                                           env=self.env,check=True,stdout=subprocess.PIPE,
                                           stderr=subprocess.PIPE,text=True)
            except subprocess.CalledProcessError as error:
                raise ValueError(f'无法读取运行中的{service}容器镜像ID') from error
            image_id = inspected.stdout.strip()
            if not re.fullmatch(r'sha256:[0-9a-f]{64}',image_id):
                raise ValueError(f'运行中的{service}容器没有有效镜像ID')
            result[service] = image_id
        return result

    def backup(self):
        destination = self.backup_destination()
        image_ids = self.resolve_image_ids()
        with self.use_image_ids(image_ids):
            manifest = self.backup_to(destination, image_ids=image_ids)
        self.verify(destination)
        print(json.dumps({'backup':str(destination),'verified':True,'image_ids':manifest['images'],
                          'message':'备份完成并已校验'},ensure_ascii=False))

    def backup_to(self, destination, resume=True, image_ids=None):
        image_ids = image_ids or self.resolve_image_ids()
        with self.use_image_ids(image_ids):
            return self._backup_to(destination, resume, image_ids)

    def _backup_to(self, destination, resume, image_ids):
        destination.mkdir(mode=0o700, parents=True, exist_ok=False)
        state = self.state()
        if any(item['Service']=='migrate' and item.get('State')=='running' for item in state):
            raise ValueError('Refusing backup while a migration is running')
        if 'report-worker' not in image_ids and any(
                item['Service'] == 'report-worker' and item.get('State') not in ('exited', 'dead', 'created')
                for item in state):
            raise ValueError('运行中的report-worker不在当前Compose配置中，无法安全停写')
        runtime_image_ids = self.active_image_ids(state,image_ids)
        writers = ['frontend', 'backend'] + (['report-worker'] if 'report-worker' in image_ids else [])
        if any(item['Service'] in writers and item.get('State') not in ('running', 'exited', 'dead', 'created')
               for item in state):
            raise ValueError('业务服务状态不稳定，无法安全停写备份')
        running = [item['Service'] for item in state
                   if item['Service'] in writers and item.get('State') == 'running']
        try:
            # All application writers stop before the SQL snapshot and filesystem archives.
            self.compose('stop', '--timeout', '60', *writers)
            self.ensure_stopped(writers)
            with (destination/'database.sql').open('wb') as target:
                self.compose('exec', '-T', 'mysql', 'sh', '-c',
                             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction '
                             '--no-tablespaces --set-gtid-purged=OFF --routines --triggers --hex-blob oj_db', stdout=target)
            for label, directory in VOLUMES.items():
                with (destination/(label+'.tar.gz')).open('wb') as target:
                    self.volume(label, '-C', directory, '-czf', '-', '.', stdout=target)
            manifest = {'format': 1, 'database_version': self.sql("SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1;"), 'created_at': dt.datetime.now(dt.timezone.utc).isoformat(),
                        'images': runtime_image_ids, 'maintenance_image_ids': image_ids,
                        'running_services': sorted(running),
                        'files': {p.name: sha256(p) for p in sorted(destination.iterdir()) if p.is_file()}}
            (destination/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
            for path in destination.iterdir():
                path.chmod(0o600)
            return manifest
        finally:
            if running and resume:
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
        try:
            manifest = json.loads((source/'manifest.json').read_text())
        except (OSError, json.JSONDecodeError) as error:
            raise ValueError('Backup manifest is missing or invalid') from error
        expected = {'database.sql', *(label+'.tar.gz' for label in VOLUMES)}
        files = manifest.get('files') if isinstance(manifest, dict) else None
        if not isinstance(manifest, dict) or manifest.get('format') != 1 or not isinstance(files, dict) or set(files) != expected:
            raise ValueError('Unsupported or incomplete backup manifest')
        for name, digest in files.items():
            if not isinstance(digest, str) or not re.fullmatch(r'[0-9a-f]{64}', digest) or sha256(source/name) != digest:
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
        manifest = self.verify(source)  # Validate before changing any target resources.
        if not self.args.confirm_empty_target:
            raise ValueError('Restore requires --confirm-empty-target and a NEW Compose project')
        image_ids = self.resolve_image_ids()
        source_image_ids = manifest.get('images')
        if getattr(self.args,'skip_migrations',False):
            if isinstance(source_image_ids,dict) and any(
                    source_image_ids.get(service) != image_ids[service] for service in ('backend','frontend')):
                raise ValueError('skip-migrations要求恢复备份记录的同一组应用镜像；当前镜像身份不匹配')
            recorded_images = source_image_ids if isinstance(source_image_ids,dict) else {}
            source_running = manifest.get('running_services')
            worker_was_running = isinstance(source_running, list) and 'report-worker' in source_running
            if ('report-worker' in image_ids
                    and (recorded_images.get('report-worker') is not None or worker_was_running)
                    and recorded_images.get('report-worker') != image_ids['report-worker']):
                raise ValueError('skip-migrations要求报告worker使用备份记录的同一镜像')
        # Keep restore-time migrations, volume extraction tools and services on one image identity.
        with self.use_image_ids(image_ids):
            self._restore(source, image_ids, source_image_ids, manifest.get('running_services'))

    def _restore(self, source, image_ids, source_image_ids, source_running_services):
        # A running or populated target is never overwritten by this helper.
        if any(item.get('State') not in ('exited', 'dead', 'created')
               and item['Service'] in ('backend','frontend','report-worker')
               for item in self.state()):
            raise ValueError('Refusing restore into a running application')
        volumes = subprocess.run(['docker','volume','ls','--filter',
                                  'label=com.docker.compose.project='+self.args.project,'-q'],
                                 check=True,stdout=subprocess.PIPE).stdout
        if volumes.strip():
            raise ValueError('Refusing restore: target project already has volumes; use a NEW project name')
        phase = 'database/cache startup'
        try:
            self.compose('up', '-d', '--wait', 'mysql', 'redis')
            phase = 'empty target verification'
            if self.sql('SELECT COUNT(*) FROM user;') != '0':
                raise ValueError('Refusing restore: target database already has users')
            for label, directory in VOLUMES.items():
                probe = self.compose('run', '--rm', '--no-deps', '-T', '--entrypoint', 'sh', 'backend', '-c',
                                     'find "$1" -mindepth 1 -print -quit', 'sh', directory, stdout=subprocess.PIPE)
                if probe.stdout.strip():
                    raise ValueError('Refusing restore: target '+label+' volume is not empty')
            phase = 'database restore'
            with (source/'database.sql').open('rb') as stream:
                self.sql(stdin=stream)
            phase = 'volume restore'
            for label, directory in VOLUMES.items():
                with (source/(label+'.tar.gz')).open('rb') as stream:
                    self.volume(label, '-C', directory, '-xzf', '-', stdin=stream)
            if not self.args.skip_migrations:
                phase = 'database migration'
                self.compose('run', '--pull', 'never', '--rm', 'migrate')
            phase = 'application startup and health check'
            services = ['backend', 'frontend']
            if 'report-worker' in image_ids and (not self.args.skip_migrations or
                    isinstance(source_running_services, list) and 'report-worker' in source_running_services):
                services.append('report-worker')
            self.compose('up', '--pull', 'never', '-d', '--wait', '--no-deps', *services)
        except (ValueError, OSError, subprocess.CalledProcessError) as error:
            # Restore targets are isolated new projects. Keep their data for diagnosis and
            # request that no partially restored application continues serving traffic.
            stop_log = None
            try:
                services = ['frontend', 'backend'] + (['report-worker'] if 'report-worker' in image_ids else [])
                self.compose('stop', '--timeout', '60', *services)
                self.ensure_stopped(services)
                stop_message = '已请求停止站点与报告工作进程。'
            except (subprocess.CalledProcessError, OSError, ValueError) as stop_failure:
                stop_message = '停止业务服务失败或状态不明，不能确认业务已停止。'
                stop_log = getattr(stop_failure, 'log_path', None)
            detail = getattr(error, 'log_path', None)
            raise ValueError(f'恢复停止在「{phase}」。来源备份已校验并保留：{source}。'
                f'{stop_message}目标项目与数据卷保留供诊断；不会自动清理或重置。请核对阶段原因后使用新的Compose项目名重试。'
                +(f' 诊断日志：{detail}' if detail else '')
                +(f' 停写诊断日志：{stop_log}' if stop_log else '')) from error
        print(json.dumps({'restored': str(source), 'project': self.args.project,
                          'checksums_verified': True,'source_image_ids':source_image_ids,
                          'image_ids':image_ids}))

    def check(self):
        issues = []
        state = self.state()
        services = {item['Service']: item for item in state}
        required = ['mysql', 'redis', 'backend', 'frontend']
        if 'report-worker' in self.configured_services():
            required.append('report-worker')
        for name in required:
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
    status = sub.add_parser('status',help='只读检查：数据库是否匹配当前程序')
    status.add_argument('--json',action='store_true',help='输出版本等诊断细节')
    upgrade = sub.add_parser('upgrade',help='先预览；--apply自动备份后升级')
    upgrade.add_argument('--apply',action='store_true')
    upgrade.add_argument('--backup-root',help='自动备份根目录，默认父级outputs/backups')
    backup = sub.add_parser('backup',help='一致备份并自动校验');backup.add_argument('--output')
    backup.add_argument('--backup-root')
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
        changes_data = args.operation in ('backup','restore') or args.operation=='upgrade' and args.apply
        if changes_data:
            with operations.maintenance_lock(): getattr(operations,args.operation)()
        else: getattr(operations,args.operation)()

if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        # Never dump subprocess command environments or connection credentials.
        if isinstance(error, subprocess.CalledProcessError):
            print('操作未完成：Docker/数据库检查失败；未自动修复历史。诊断日志：'+str(getattr(error,'log_path','请检查Docker状态和环境文件')),file=sys.stderr)
        else: print('操作未完成：'+str(error)[:700],file=sys.stderr)
        raise SystemExit(1)
