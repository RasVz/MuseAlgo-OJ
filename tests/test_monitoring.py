"""OPS-02 监控收口测试：配置契约 + 指标源码契约 + ops.py check 行为。

只依赖标准库（不 import PyYAML，不启动 Docker、数据库或 Spring）：
- 手写缩进解析 OJ-Backend/oj-admin/src/main/resources/application.yml，校验 oj.monitoring.* 默认值；
- 读取 oj-judge metrics 源码，校验指标名齐全、标签低基数、无外部告警与敏感标签；
- importlib 加载 scripts/ops.py，用 fake state/sql 校验 check() 的备份新鲜度语义
  （未配置 / 无 manifest / 损坏 / 过期 / 新鲜）与退出码；
- 校验 ops.py 的窗口口径与 application.yml 默认值一致。
"""
import contextlib
import datetime as dt
import importlib.util
import io
import json
import re
import subprocess
import tarfile
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[1]
APPLICATION_YML = REPO / 'OJ-Backend/oj-admin/src/main/resources/application.yml'
METRICS_DIR = REPO / 'OJ-Backend/oj-judge/src/main/java/com/musealgo/oj/judge/metrics'
WORKFLOW = REPO / '.github/workflows/database.yml'

SPEC = importlib.util.spec_from_file_location('musealgo_ops_monitoring', REPO / 'scripts/ops.py')
OPS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(OPS)


def parse_scalar_mappings(path):
    """极简 YAML 标量映射解析：只处理单行 key: value 与缩进嵌套，返回 {'a.b.c': 'raw'}。

    不处理列表/锚点/多行块；application.yml 的这些区段是扁平键值，够用且避免引入 PyYAML。
    """
    values = {}
    stack = []
    for raw in Path(path).read_text(encoding='utf-8').splitlines():
        if not raw.strip() or raw.lstrip().startswith('#'):
            continue
        indent = len(raw) - len(raw.lstrip(' '))
        content = raw.strip()
        if ':' not in content:
            continue
        key, _, value = content.partition(':')
        key = key.strip()
        value = value.strip()
        while stack and stack[-1][0] >= indent:
            stack.pop()
        path_parts = [item[1] for item in stack] + [key]
        if not value or value.startswith('#'):
            stack.append((indent, key))
            continue
        if ' #' in value:
            value = value.split(' #', 1)[0].strip()
        values['.'.join(path_parts)] = value
    return values


def env_default(raw):
    """从 ${ENV:default} 占位符中取出 (env 名, 默认值)；不是占位符则返回 (None, raw)。"""
    match = re.fullmatch(r'\$\{([A-Za-z_][A-Za-z0-9_]*):(.*)\}', raw)
    if match:
        return match.group(1), match.group(2)
    return None, raw


CONFIG = parse_scalar_mappings(APPLICATION_YML)


def raw_default(key):
    """取配置默认值字符串（去掉 ${ENV:...} 外壳）。"""
    return env_default(CONFIG[key])[1]


def strip_java_comments(source):
    """剥离 Java 块注释与行注释，避免仅在 Javadoc 里出现的外部渠道词触发误报。"""
    without_block = re.sub(r'/\*.*?\*/', '', source, flags=re.S)
    return re.sub(r'//[^\n]*', '', without_block)


# 指标标签只允许这些低基数枚举：key -> 允许的取值（变量取值走常量表校验）
TAG_VALUE_ENUMS = {
    'reason': {'endpoint', 'other'},
    'attempt': {'1', '2'},
    'status': {'PENDING', 'GENERATING', 'FAILED', 'DELIVERED', 'DRY_RUN'},
    'channel': {'IN_APP', 'EMAIL', 'SMS', 'WECHAT'},
}


class MonitoringConfigTest(unittest.TestCase):
    """application.yml 的 oj.monitoring.* 契约。"""

    def assert_default(self, key, expected, env=None):
        raw = CONFIG.get(key)
        self.assertIsNotNone(raw, '缺失配置键: ' + key)
        found_env, default = env_default(raw)
        self.assertEqual(expected, default, '默认值不符: ' + key)
        if env is not None:
            self.assertEqual(env, found_env, '环境变量名不符: ' + key)

    def test_monitoring_block_is_enabled_and_refreshable(self):
        self.assert_default('oj.monitoring.enabled', 'true', 'OJ_MONITORING_ENABLED')
        self.assert_default('oj.monitoring.refresh-interval-ms', '30000', 'OJ_MONITORING_REFRESH_INTERVAL_MS')

    def test_judge_graded_thresholds_exist_and_are_below_watchdog(self):
        self.assert_default('oj.monitoring.judge-processing-slow-seconds', '120',
                            'OJ_MONITORING_JUDGE_PROCESSING_SLOW_SECONDS')
        self.assert_default('oj.monitoring.judge-processing-page-seconds', '300',
                            'OJ_MONITORING_JUDGE_PROCESSING_PAGE_SECONDS')
        self.assert_default('oj.monitoring.judge-submission-inflight-slow-seconds', '180',
                            'OJ_MONITORING_JUDGE_SUBMISSION_INFLIGHT_SLOW_SECONDS')
        self.assert_default('oj.monitoring.judge-failure-window-minutes', '60',
                            'OJ_MONITORING_JUDGE_FAILURE_WINDOW_MINUTES')
        self.assert_default('oj.judge.watchdog.stuck-timeout-ms', '600000', 'OJ_JUDGE_WATCHDOG_STUCK_MS')
        # 分级阈值必须早于看门狗回收阈值，否则告警永远来不及
        self.assertLess(int(raw_default('oj.monitoring.judge-processing-slow-seconds')),
                        int(raw_default('oj.judge.watchdog.stuck-timeout-ms')) // 1000)
        self.assertLess(int(raw_default('oj.monitoring.judge-processing-page-seconds')),
                        int(raw_default('oj.judge.watchdog.stuck-timeout-ms')) // 1000)

    def test_settlement_outbox_report_notification_thresholds(self):
        self.assert_default('oj.monitoring.contest-settlement-grace-minutes', '15',
                            'OJ_MONITORING_CONTEST_SETTLEMENT_GRACE_MINUTES')
        self.assert_default('oj.monitoring.outbox-failed-window-minutes', '60',
                            'OJ_MONITORING_OUTBOX_FAILED_WINDOW_MINUTES')
        self.assert_default('oj.monitoring.outbox-pending-warning', '100',
                            'OJ_MONITORING_OUTBOX_PENDING_WARNING')
        self.assert_default('oj.monitoring.outbox-oldest-pending-warning-seconds', '300',
                            'OJ_MONITORING_OUTBOX_OLDEST_PENDING_WARNING_SECONDS')
        self.assert_default('oj.monitoring.report-wait-warning-seconds', '1800',
                            'OJ_MONITORING_REPORT_WAIT_WARNING_SECONDS')
        self.assert_default('oj.monitoring.notification-missing-warning-seconds', '600',
                            'OJ_MONITORING_NOTIFICATION_MISSING_WARNING_SECONDS')

    def test_backup_monitoring_defaults_to_unconfigured(self):
        self.assert_default('oj.monitoring.backup.root', '', 'OJ_MONITORING_BACKUP_ROOT')
        self.assert_default('oj.monitoring.backup.max-age-hours', '26', 'OJ_MONITORING_BACKUP_MAX_AGE_HOURS')
        self.assert_default('oj.monitoring.backup.verify-checksums', 'true',
                            'OJ_MONITORING_BACKUP_VERIFY_CHECKSUMS')

    def test_prometheus_exposure_already_enabled(self):
        self.assertIn('prometheus', CONFIG.get('management.endpoints.web.exposure.include', ''))
        self.assertEqual('oj', env_default(CONFIG.get('management.metrics.tags.application', ''))[1])

    def test_ci_runs_monitoring_unittests(self):
        workflow = WORKFLOW.read_text(encoding='utf-8')
        self.assertIn('tests.test_monitoring', workflow)


class MetricsSourceContractTest(unittest.TestCase):
    """oj-judge metrics 源码契约：指标名、标签基数、无外部告警、备份未配置不健康。"""

    @classmethod
    def setUpClass(cls):
        cls.health = (METRICS_DIR / 'OpsHealthMetrics.java').read_text(encoding='utf-8')
        cls.snapshot = (METRICS_DIR / 'OpsSnapshot.java').read_text(encoding='utf-8')
        cls.backup = (METRICS_DIR / 'BackupFreshnessChecker.java').read_text(encoding='utf-8')
        cls.backup_status = (METRICS_DIR / 'BackupStatus.java').read_text(encoding='utf-8')

    def test_all_contract_metrics_are_registered(self):
        expected = [
            'oj.judge.stuck', 'oj.judge.processing.slow', 'oj.judge.processing.oldest.seconds',
            'oj.judge.failed.recent', 'oj.judge.failure.reason', 'oj.judge.retry.exhausted.recent',
            'oj.judge.retry.pending', 'oj.judge.submission.inflight.slow',
            'oj.judge.submission.inflight.oldest.seconds', 'oj.contest.unsettled',
            'oj.contest.unsettled.oldest.seconds', 'oj.outbox.pending', 'oj.outbox.due',
            'oj.outbox.oldest.pending.seconds', 'oj.outbox.stuck.pending', 'oj.outbox.failed.recent',
            'oj.outbox.backlog.warning', 'oj.report.jobs.active', 'oj.report.wait.oldest.seconds',
            'oj.report.retry.exhausted', 'oj.report.delivery', 'oj.notification.report.missing',
            'oj.backup.configured', 'oj.backup.healthy', 'oj.backup.age.seconds', 'oj.backup.corrupt',
            'oj.backup.last.check.ok', 'oj.ops.health.status', 'oj.monitoring.snapshot.age.seconds',
            'oj.monitoring.stale', 'oj.monitoring.refresh.failures',
        ]
        for name in expected:
            self.assertIn('"' + name + '"', self.health, '缺少指标: ' + name)

    def test_tags_are_low_cardinality_enums_only(self):
        code = strip_java_comments(self.health)
        # 只在 Tags.of(...) 内部解析 "key", "literal" 或 "key", loopVariable 的标签对
        pairs = []
        for inner in re.findall(r'Tags\.of\(([^)]*)\)', code):
            pairs.extend(re.findall(r'"([A-Za-z]+)"\s*,\s*(?:"([^"]*)"|([A-Za-z_][A-Za-z0-9_.]*))', inner))
        self.assertTrue(pairs, '未找到任何 Tags.of 标签对')
        for key, literal, variable in pairs:
            self.assertIn(key, TAG_VALUE_ENUMS, '发现高基数/未登记标签键: ' + key)
            if literal:
                self.assertIn(literal, TAG_VALUE_ENUMS[key], '标签值不在低基数枚举内: %s=%s' % (key, literal))
                continue
            # 变量取值必须来自常量枚举清单：for (String x : SOME_LIST)
            lists = re.findall(r'for \(String %s : ([A-Z_]+)\)' % re.escape(variable), code)
            self.assertTrue(lists, '标签变量 %s 不是遍历常量清单（无法证明低基数）' % variable)
            for constant in lists:
                declared = re.search(constant + r' = List\.of\(([^)]*)\)', code)
                self.assertIsNotNone(declared, '常量清单缺失: ' + constant)
                values = set(re.findall(r'"([^"]*)"', declared.group(1)))
                self.assertTrue(values <= TAG_VALUE_ENUMS[key],
                                '常量清单 %s 含未登记取值: %s' % (constant, sorted(values - TAG_VALUE_ENUMS[key])))
        # 禁止把身份/内容/错误原文放进标签（注释里可以出现说明性文字，字面量不允许）
        for forbidden in ('userId', 'jobId', 'submissionId', 'studentId', 'classId',
                          'studentName', 'recipientId', 'traceId'):
            self.assertNotIn('"%s"' % forbidden, code, '标签或指标名出现敏感键: ' + forbidden)

    def test_health_requires_configured_and_healthy_backup(self):
        health_method = self.snapshot.split('healthStatus', 1)[1]
        self.assertIn('configured()', health_method)
        self.assertIn('healthy()', health_method)
        self.assertIn('corrupt()', health_method)
        self.assertIn('dbOk', health_method)
        # 未配置构造器：configured=false、healthy=false、age=-1（实现跨行，显式允许空白/换行）
        self.assertRegex(self.backup_status,
                         r'notConfigured\(\)\s*\{\s*return new BackupStatus\(false, false, -1L',
                         )
        # 文件系统检查覆盖 manifest 四件产物与 sha256
        for name in ('database.sql', 'testcases.tar.gz', 'uploads.tar.gz', 'coursewares.tar.gz'):
            self.assertIn(name, self.backup)

    def test_refresh_is_guarded_and_degrades_instead_of_crashing(self):
        self.assertIn('@Scheduled(fixedDelayString = "${oj.monitoring.refresh-interval-ms:30000}")',
                      self.health)
        self.assertIn('AtomicReference<OpsSnapshot>', self.health)
        self.assertIn('OpsSnapshot.unknown(', self.health)
        self.assertIn('refreshFailure("db")', self.health)
        self.assertIn('refreshFailure("backup")', self.health)
        self.assertIn('matchIfMissing = true', self.health)

    def test_no_external_alert_channel_is_implemented(self):
        # 注释里允许写「不接 Webhook/SMTP」这类边界说明，只校验可执行代码
        forbidden = ('JavaMailSender', 'Webhook', 'webhook', 'RestTemplate', 'HttpClient',
                     'URLConnection', 'Telegram', 'Slack', 'SMTP', 'NotificationService',
                     'MailSender', 'HttpRequest')
        combined = ''.join(strip_java_comments(source)
                           for source in (self.health, self.snapshot, self.backup, self.backup_status))
        for token in forbidden:
            self.assertNotIn(token, combined, '监控实现不得接入外部告警渠道: ' + token)


class CheckBackupFreshnessTest(unittest.TestCase):
    """scripts/ops.py check()：备份新鲜度/损坏语义与退出码（fake state/sql，不启动 Docker）。"""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def operations(self, backup_root=None, max_age_hours=26):
        args = Namespace(env_file='.env', compose='docker-compose.prod.yml', project='isolated',
                         backup_root=backup_root, max_backup_age_hours=max_age_hours)
        return OPS.Operations(args)

    def services(self):
        return [{'Service': name, 'State': 'running', 'Health': 'healthy'}
                for name in ('mysql', 'redis', 'backend', 'frontend')]

    def run_check(self, operations, counters='0'):
        stdout = io.StringIO()
        with patch.object(operations, 'state', return_value=self.services()), \
             patch.object(operations, 'sql', side_effect=lambda *a, **k: counters), \
             patch.object(operations, 'compose', return_value=subprocess.CompletedProcess([], 0, stdout=b'{"services":{}}')), \
             contextlib.redirect_stdout(stdout):
            code = 0
            try:
                operations.check()
            except SystemExit as exit_signal:
                code = exit_signal.code
        return code, json.loads(stdout.getvalue())

    def build_backup(self, created_at=None, corrupt=False):
        directory = self.root / 'backup-2026-10-21'
        directory.mkdir()
        (directory / 'database.sql').write_text('SELECT 1;')
        for name in OPS.VOLUMES:
            with tarfile.open(directory / (name + '.tar.gz'), 'w:gz'):
                pass
        files = {p.name: OPS.sha256(p) for p in sorted(directory.iterdir()) if p.is_file()}
        manifest = {'format': 1, 'database_version': '62',
                    'created_at': (created_at or dt.datetime.now(dt.timezone.utc)).isoformat(),
                    'files': files}
        (directory / 'manifest.json').write_text(json.dumps(manifest))
        if corrupt:
            (directory / 'database.sql').write_text('modified')
        return directory

    def test_unconfigured_backup_root_is_reported_not_healthy(self):
        code, report = self.run_check(self.operations(backup_root=None))
        self.assertEqual(1, code)
        self.assertFalse(report['ok'])
        self.assertIn('backup freshness was not configured (--backup-root)', report['issues'])

    def test_empty_backup_root_reports_no_manifest(self):
        code, report = self.run_check(self.operations(backup_root=str(self.root)))
        self.assertEqual(1, code)
        self.assertIn('no backup manifest found', report['issues'])

    def test_corrupt_backup_is_reported(self):
        self.build_backup(corrupt=True)
        code, report = self.run_check(self.operations(backup_root=str(self.root)))
        self.assertEqual(1, code)
        self.assertIn('latest backup is corrupt', report['issues'])

    def test_stale_backup_is_reported(self):
        self.build_backup(created_at=dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=27))
        code, report = self.run_check(self.operations(backup_root=str(self.root)))
        self.assertEqual(1, code)
        self.assertIn('backup is too old', report['issues'])

    def test_fresh_verified_backup_is_ok(self):
        self.build_backup()
        code, report = self.run_check(self.operations(backup_root=str(self.root)))
        self.assertEqual(0, code)
        self.assertTrue(report['ok'])
        self.assertEqual([], report['issues'])

    def test_service_and_database_signals_are_reported_in_order(self):
        operations = self.operations(backup_root=None)
        broken = [{'Service': 'mysql', 'State': 'exited', 'Health': ''}]
        stdout = io.StringIO()
        with patch.object(operations, 'state', return_value=broken), \
             patch.object(operations, 'sql',
                          side_effect=lambda query, *a, **k: '2' if 'outbox_event' in query else '0'), \
             patch.object(operations, 'compose', return_value=subprocess.CompletedProcess([], 0, stdout=b'{"services":{}}')), \
             contextlib.redirect_stdout(stdout):
            with self.assertRaises(SystemExit) as raised:
                operations.check()
        self.assertEqual(1, raised.exception.code)
        report = json.loads(stdout.getvalue())
        self.assertEqual(2, report['failed_events_last_hour'])
        self.assertIn('2 failed outbox events in the last hour', report['issues'])
        self.assertIn('mysql is not ready', report['issues'])
        self.assertLess(report['issues'].index('2 failed outbox events in the last hour'),
                        report['issues'].index('backup freshness was not configured (--backup-root)'))


class OpsPythonWindowsAlignWithConfigTest(unittest.TestCase):
    """ops.py check 的 SQL 窗口口径与 application.yml 默认值一致（单一事实来源避免漂移）。"""

    @classmethod
    def setUpClass(cls):
        cls.ops_source = (REPO / 'scripts/ops.py').read_text(encoding='utf-8')

    def test_stalled_judge_window_matches_watchdog_timeout(self):
        self.assertIn('INTERVAL 10 MINUTE', self.ops_source)
        self.assertEqual(10 * 60 * 1000, int(raw_default('oj.judge.watchdog.stuck-timeout-ms')))

    def test_failure_windows_match_config(self):
        self.assertGreaterEqual(self.ops_source.count('INTERVAL 1 HOUR'), 2)
        for key in ('oj.monitoring.judge-failure-window-minutes',
                    'oj.monitoring.outbox-failed-window-minutes'):
            self.assertEqual(60, int(raw_default(key)), key)

    def test_settlement_grace_matches_config(self):
        self.assertIn('INTERVAL 15 MINUTE', self.ops_source)
        self.assertEqual(15, int(raw_default('oj.monitoring.contest-settlement-grace-minutes')))

    def test_backup_max_age_matches_config(self):
        self.assertIn('--max-backup-age-hours', self.ops_source)
        self.assertRegex(self.ops_source, r'--max-backup-age-hours[^\n]*default=26')
        self.assertEqual(26, int(raw_default('oj.monitoring.backup.max-age-hours')))


if __name__ == '__main__':
    unittest.main()
