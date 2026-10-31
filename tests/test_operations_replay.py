"""OPS-01：结算链式重放（contest settlement replay）离线契约与边界测试。

本文件不启动数据库或应用栈，只读取仓库内的 Java 源码、迁移脚本与部署配置，
并用纯 Python 模型复现重放服务的保护逻辑，锁定以下运维契约：

1. 维护开关默认关闭（application.yml / docker-compose.prod.yml / compose.dev.yml）；
2. 链长度保护上限 50 场、成绩行数保护上限 20000 行，超限拒绝执行；
3. 有界比较用严格大于（50/20000 恰好通过，51/20001 拒绝）；
4. 预览确认串绑定当前源数据指纹（REPLAY:/RESTORE: 前缀 + SHA-256）；
5. 重放记录状态判定：仅 COMPLETED 且为最近一条成功记录、重放后数据未变化时可反向恢复；
6. 迁移 V59/V61 的状态列与恢复审计列存在。

注意：50/20000 是保护上限而不是容量 SLA；真实并发、锁等待与事务耗时必须在
MySQL/Compose 演练中实测（见 docs/OPERATIONS.md 与 operations_drill.py）。
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVICE = (
    ROOT
    / "OJ-Backend/oj-contest/src/main/java/com/musealgo/oj/contest/service"
    / "ContestSettlementReplayService.java"
)
CONTROLLER = (
    ROOT
    / "OJ-Backend/oj-contest/src/main/java/com/musealgo/oj/contest/controller"
    / "AdminSettlementReplayController.java"
)
MIGRATION_V59 = ROOT / "OJ-Backend/oj-admin/src/main/resources/db/migration/V59__contest_settlement_replay.sql"
MIGRATION_V61 = ROOT / "OJ-Backend/oj-admin/src/main/resources/db/migration/V61__settlement_replay_restore_audit.sql"
APPLICATION_YML = ROOT / "OJ-Backend/oj-admin/src/main/resources/application.yml"
COMPOSE_PROD = ROOT / "docker-compose.prod.yml"
COMPOSE_DEV = ROOT / "compose.dev.yml"

MAX_CONTESTS = 50
MAX_ROWS = 20000
REASON_MAX_LENGTH = 500


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def service_source() -> str:
    return read(SERVICE)


# ---------------------------------------------------------------------------
# 纯 Python 契约模型：语义必须与 ContestSettlementReplayService 的实现一致。
# ---------------------------------------------------------------------------


def select_replay_chain(contests, from_contest_id):
    """复现 state(fromId) 的链选择：起点必须已固化，链为结束时间更晚，或结束时间相同且 id >= 起点。"""
    # 等价于 selectFinalizedForReplay() 的 ORDER BY end_time, id；链的对外顺序即此顺序。
    contests = sorted(contests, key=lambda c: (c[1] is None, c[1] or "", c[0]))
    from_contest = next((c for c in contests if c[0] == from_contest_id), None)
    if from_contest is None:
        raise ValueError("起点比赛不存在或尚未固化")
    if from_contest[1] is None:
        raise ValueError("起点比赛缺少结束时间")
    end = from_contest[1]
    return [
        contest
        for contest in contests
        if contest[1] is not None
        and (contest[1] > end or (contest[1] == end and contest[0] >= from_contest_id))
    ]


def replay_warnings(*, enabled, contest_count, old_rows, new_rows, pending_contest_ids=()):
    """复现 previewOf 的硬性拒绝条件（与实现保持同一顺序与文案）。"""
    warnings = []
    if not enabled:
        warnings.append("链式重放维护开关未开启")
    if contest_count > MAX_CONTESTS:
        warnings.append("链长度超过 " + str(MAX_CONTESTS))
    if old_rows > MAX_ROWS:
        warnings.append("旧成绩行数超过 " + str(MAX_ROWS))
    for contest_id in pending_contest_ids:
        warnings.append("比赛 #" + str(contest_id) + " 仍有待判提交")
    if new_rows > MAX_ROWS:
        warnings.append("预计新成绩行数超过 " + str(MAX_ROWS))
    return warnings


def can_execute(**kwargs):
    return not replay_warnings(**kwargs)


def restore_warnings(*, status, replay_id, latest_completed_id, backup_version, after_fingerprint, current_fingerprint):
    """复现 previewRestore 的恢复前置条件；restorableBackup 的异常折算为一条不可执行警告。"""
    warnings = []
    if status != "COMPLETED":
        warnings.append("仅成功且未恢复的重放可恢复")
        return warnings
    if backup_version != 2 or not after_fingerprint:
        warnings.append("旧版或不完整备份不能自动恢复，请走数据库备份恢复流程")
        return warnings
    if after_fingerprint != current_fingerprint:
        warnings.append("重放后数据已变化，不能覆盖后续写入；请使用数据库备份恢复流程")
    if latest_completed_id != replay_id:
        warnings.append("只能恢复最近一条尚未恢复的成功重放")
    return warnings


def valid_reason(reason):
    return reason is not None and reason.strip() != "" and len(reason) <= REASON_MAX_LENGTH


def confirmation_matches(preview_confirmation, submitted):
    return submitted is not None and preview_confirmation == submitted.strip()


# ---------------------------------------------------------------------------
# 1. 常量与实现守卫
# ---------------------------------------------------------------------------


class ServiceBoundaryContractTest(unittest.TestCase):
    def test_protection_limits(self):
        source = service_source()
        contests = re.search(r"public static final int MAX_CONTESTS = (\d+);", source)
        rows = re.search(r"public static final int MAX_ROWS = (\d+);", source)
        self.assertIsNotNone(contests, "未找到 MAX_CONTESTS 常量")
        self.assertIsNotNone(rows, "未找到 MAX_ROWS 常量")
        self.assertEqual(int(contests.group(1)), 50)
        self.assertEqual(int(rows.group(1)), 20000)

    def test_each_cap_is_used_with_strict_greater_than(self):
        source = service_source()
        # 恰好等于上限必须放行，只有超过才拒绝（边界不是 >=）。
        self.assertIn("state.contests().size() > MAX_CONTESTS", source)
        self.assertIn("state.ranks().size() > MAX_ROWS", source)
        self.assertIn("if (newRows > MAX_ROWS)", source)
        self.assertNotIn("state.contests().size() >= MAX_CONTESTS", source)
        self.assertNotIn("state.ranks().size() >= MAX_ROWS", source)
        self.assertNotIn("newRows >= MAX_ROWS", source)

    def test_can_execute_requires_empty_warnings(self):
        source = service_source()
        self.assertIn("warnings.isEmpty()", source)
        self.assertIn("!preview.canExecute()", source)
        self.assertIn('String.join("；", preview.warnings())', source)

    def test_chain_selection_predicate_present(self):
        source = service_source()
        mapper = read(SERVICE.parents[1] / "mapper/ContestMapper.java")
        self.assertIn("ORDER BY end_time, id", mapper)
        self.assertIn("c.getEndTime().isAfter(from.getEndTime())", source)
        self.assertIn("c.getEndTime().equals(from.getEndTime()) && c.getId() >= fromId", source)
        self.assertIn("起点比赛不存在或尚未固化", source)
        self.assertIn("起点比赛缺少结束时间", source)

    def test_confirmation_binds_fingerprint_and_prefix(self):
        source = service_source()
        self.assertIn('"REPLAY:" + fromContestId + ":"', source)
        self.assertIn('"RESTORE:" + replayId + ":"', source)
        self.assertIn('MessageDigest.getInstance("SHA-256")', source)
        self.assertIn("预览已变化或确认串不匹配，请重新预览", source)

    def test_reason_and_switch_guards_present(self):
        source = service_source()
        self.assertIn("reason.length() > 500", source)
        self.assertIn("必须填写 1~500 字的操作原因", source)
        self.assertIn('@Value("${contest.replay.enabled:false}")', source)
        self.assertIn("链式重放维护开关未开启", source)


class ReplayChainModelTest(unittest.TestCase):
    def test_chain_includes_later_and_same_end_time_later_id(self):
        contests = [(5, "2026-01-03T00:00:00"), (3, "2026-01-02T00:00:00"), (4, "2026-01-02T00:00:00")]
        chain = select_replay_chain(contests, 3)
        self.assertEqual([contest[0] for contest in chain], [3, 4, 5])

    def test_chain_excludes_earlier_contests(self):
        contests = [(1, "2026-01-01T00:00:00"), (2, "2026-01-02T00:00:00"), (3, "2026-01-03T00:00:00")]
        chain = select_replay_chain(contests, 2)
        self.assertEqual([contest[0] for contest in chain], [2, 3])

    def test_same_end_time_earlier_id_is_not_in_chain(self):
        contests = [(1, "2026-01-02T00:00:00"), (2, "2026-01-02T00:00:00"), (3, "2026-01-02T00:00:00")]
        chain = select_replay_chain(contests, 2)
        self.assertEqual([contest[0] for contest in chain], [2, 3])

    def test_missing_or_unfinalized_origin_rejected(self):
        with self.assertRaisesRegex(ValueError, "起点比赛不存在或尚未固化"):
            select_replay_chain([(2, "2026-01-02T00:00:00")], 1)
        with self.assertRaisesRegex(ValueError, "起点比赛缺少结束时间"):
            select_replay_chain([(1, None), (2, "2026-01-02T00:00:00")], 1)


# ---------------------------------------------------------------------------
# 2. 50 场 / 20000 行 保护上限与超限拒绝
# ---------------------------------------------------------------------------


class ContestCapTest(unittest.TestCase):
    def test_exactly_50_contests_allowed(self):
        self.assertEqual(replay_warnings(enabled=True, contest_count=50, old_rows=0, new_rows=0), [])
        self.assertTrue(can_execute(enabled=True, contest_count=50, old_rows=0, new_rows=0))

    def test_51_contests_rejected_with_explicit_warning(self):
        warnings = replay_warnings(enabled=True, contest_count=51, old_rows=0, new_rows=0)
        self.assertEqual(warnings, ["链长度超过 50"])
        self.assertFalse(can_execute(enabled=True, contest_count=51, old_rows=0, new_rows=0))

    def test_large_chain_rejected(self):
        warnings = replay_warnings(enabled=True, contest_count=500, old_rows=0, new_rows=0)
        self.assertIn("链长度超过 50", warnings)


class RowCapTest(unittest.TestCase):
    def test_old_rows_boundary(self):
        for allowed in (0, 1, 19999, 20000):
            with self.subTest(old_rows=allowed):
                self.assertEqual(replay_warnings(enabled=True, contest_count=1, old_rows=allowed, new_rows=0), [])
        for rejected in (20001, 100000):
            with self.subTest(old_rows=rejected):
                self.assertEqual(
                    replay_warnings(enabled=True, contest_count=1, old_rows=rejected, new_rows=0),
                    ["旧成绩行数超过 20000"],
                )

    def test_new_rows_boundary(self):
        for allowed in (0, 1, 19999, 20000):
            with self.subTest(new_rows=allowed):
                self.assertEqual(replay_warnings(enabled=True, contest_count=1, old_rows=0, new_rows=allowed), [])
        for rejected in (20001, 100000):
            with self.subTest(new_rows=rejected):
                self.assertEqual(
                    replay_warnings(enabled=True, contest_count=1, old_rows=0, new_rows=rejected),
                    ["预计新成绩行数超过 20000"],
                )

    def test_both_caps_exceeded_reports_both(self):
        warnings = replay_warnings(enabled=True, contest_count=51, old_rows=20001, new_rows=20001)
        self.assertEqual(
            warnings,
            ["链长度超过 50", "旧成绩行数超过 20000", "预计新成绩行数超过 20000"],
        )

    def test_pending_submission_and_cap_can_coexist(self):
        warnings = replay_warnings(enabled=True, contest_count=1, old_rows=0, new_rows=0, pending_contest_ids=(7,))
        self.assertEqual(warnings, ["比赛 #7 仍有待判提交"])


# ---------------------------------------------------------------------------
# 3. 维护开关、确认串与操作原因
# ---------------------------------------------------------------------------


class MaintenanceGateTest(unittest.TestCase):
    def test_switch_off_always_blocks_even_with_small_chain(self):
        warnings = replay_warnings(enabled=False, contest_count=1, old_rows=0, new_rows=0)
        self.assertEqual(warnings, ["链式重放维护开关未开启"])
        self.assertFalse(can_execute(enabled=False, contest_count=1, old_rows=0, new_rows=0))

    def test_switch_on_small_chain_executable(self):
        self.assertTrue(can_execute(enabled=True, contest_count=1, old_rows=10, new_rows=10))


class ConfirmationAndReasonTest(unittest.TestCase):
    def test_reason_between_1_and_500_characters(self):
        self.assertTrue(valid_reason("a"))
        self.assertTrue(valid_reason("x" * 500))
        self.assertFalse(valid_reason(""))
        self.assertFalse(valid_reason("   "))
        self.assertFalse(valid_reason(None))
        self.assertFalse(valid_reason("x" * 501))

    def test_confirmation_must_match_preview_fingerprint(self):
        expected = "REPLAY:7:" + "a" * 64
        self.assertTrue(confirmation_matches(expected, expected))
        self.assertTrue(confirmation_matches(expected, "  " + expected + "  "))
        self.assertFalse(confirmation_matches(expected, "REPLAY:7:" + "b" * 64))
        self.assertFalse(confirmation_matches(expected, "RESTORE:7:" + "a" * 64))
        self.assertFalse(confirmation_matches(expected, None))
        self.assertFalse(confirmation_matches(expected, ""))


# ---------------------------------------------------------------------------
# 4. 重放记录状态判定与反向恢复边界
# ---------------------------------------------------------------------------


class ReplayStatusTest(unittest.TestCase):
    def test_only_completed_is_restorable(self):
        for status in ("RUNNING", "FAILED", "RESTORED", "UNKNOWN"):
            with self.subTest(status=status):
                warnings = restore_warnings(
                    status=status,
                    replay_id=3,
                    latest_completed_id=3,
                    backup_version=2,
                    after_fingerprint="f" * 64,
                    current_fingerprint="f" * 64,
                )
                self.assertEqual(warnings, ["仅成功且未恢复的重放可恢复"])

    def test_completed_and_unchanged_is_restorable(self):
        warnings = restore_warnings(
            status="COMPLETED",
            replay_id=3,
            latest_completed_id=3,
            backup_version=2,
            after_fingerprint="f" * 64,
            current_fingerprint="f" * 64,
        )
        self.assertEqual(warnings, [])

    def test_later_write_blocks_reverse_restore(self):
        warnings = restore_warnings(
            status="COMPLETED",
            replay_id=3,
            latest_completed_id=3,
            backup_version=2,
            after_fingerprint="f" * 64,
            current_fingerprint="0" * 64,
        )
        self.assertEqual(warnings, ["重放后数据已变化，不能覆盖后续写入；请使用数据库备份恢复流程"])

    def test_only_latest_successful_replay_is_restorable(self):
        warnings = restore_warnings(
            status="COMPLETED",
            replay_id=3,
            latest_completed_id=4,
            backup_version=2,
            after_fingerprint="f" * 64,
            current_fingerprint="f" * 64,
        )
        self.assertEqual(warnings, ["只能恢复最近一条尚未恢复的成功重放"])

    def test_old_or_incomplete_backup_is_not_auto_restorable(self):
        for version, fingerprint in ((1, "f" * 64), (3, "f" * 64), (2, None), (2, "")):
            with self.subTest(version=version, fingerprint=fingerprint):
                warnings = restore_warnings(
                    status="COMPLETED",
                    replay_id=3,
                    latest_completed_id=3,
                    backup_version=version,
                    after_fingerprint=fingerprint,
                    current_fingerprint="f" * 64,
                )
                self.assertEqual(warnings, ["旧版或不完整备份不能自动恢复，请走数据库备份恢复流程"])

    def test_multiple_restore_blockers_are_all_reported(self):
        warnings = restore_warnings(
            status="COMPLETED",
            replay_id=3,
            latest_completed_id=4,
            backup_version=2,
            after_fingerprint="f" * 64,
            current_fingerprint="0" * 64,
        )
        self.assertEqual(len(warnings), 2)

    def test_transactional_failure_leaves_no_failed_replay_record(self):
        # 实现里 RUNNING 记录与修复写入在同一事务，异常时数据库回滚，
        # 因此不会出现 FAILED 记录；失败尝试只能依赖管理接口审计日志。
        source = service_source()
        self.assertIn("@Transactional(rollbackFor = Exception.class)\n    public SettlementReplayResultVO execute", source)
        self.assertIn('replay.setStatus("RUNNING")', source)
        self.assertIn('replay.setStatus("COMPLETED")', source)
        self.assertNotIn('setStatus("FAILED")', source)


# ---------------------------------------------------------------------------
# 5. 部署配置、控制器鉴权/审计与迁移契约
# ---------------------------------------------------------------------------


class DeploymentConfigContractTest(unittest.TestCase):
    def test_replay_switch_defaults_to_false_everywhere(self):
        self.assertIn("enabled: ${OJ_SETTLEMENT_REPLAY_ENABLED:false}", read(APPLICATION_YML))
        self.assertIn("OJ_SETTLEMENT_REPLAY_ENABLED: ${OJ_SETTLEMENT_REPLAY_ENABLED:-false}", read(COMPOSE_PROD))
        self.assertIn("OJ_SETTLEMENT_REPLAY_ENABLED: ${OJ_SETTLEMENT_REPLAY_ENABLED:-false}", read(COMPOSE_DEV))

    def test_prod_compose_keeps_switch_off_without_explicit_env(self):
        prod = read(COMPOSE_PROD)
        match = re.search(r"OJ_SETTLEMENT_REPLAY_ENABLED: \$\{OJ_SETTLEMENT_REPLAY_ENABLED:-([^}]+)\}", prod)
        self.assertIsNotNone(match, "生产 Compose 未声明重放开关默认值")
        self.assertEqual(match.group(1), "false")


class ControllerContractTest(unittest.TestCase):
    def setUp(self):
        self.source = read(CONTROLLER)

    def test_all_endpoints_require_admin(self):
        endpoints = re.findall(r"@(?:Get|Post)Mapping", self.source)
        guards = re.findall(r"@PreAuthorize\(\"hasRole\('ADMIN'\)\"\)", self.source)
        self.assertEqual(len(endpoints), 6)
        self.assertEqual(len(guards), len(endpoints))

    def test_high_risk_operations_are_audited(self):
        self.assertIn('@AuditLog(module = "contest", action = "replay_settlement_chain")', self.source)
        self.assertIn('@AuditLog(module = "contest", action = "restore_settlement_replay")', self.source)
        self.assertIn('@AuditLog(module = "contest", action = "read_replay_backup")', self.source)

    def test_replay_endpoints_route_through_service(self):
        for snippet in (
            "replayService.preview(contestId)",
            "replayService.execute(",
            "replayService.history()",
            "replayService.getReplay(replayId)",
            "replayService.previewRestore(replayId)",
            "replayService.restore(replayId",
        ):
            with self.subTest(snippet=snippet):
                self.assertIn(snippet, self.source)


class MigrationContractTest(unittest.TestCase):
    def test_v59_records_replay_attempts_and_backup(self):
        migration = read(MIGRATION_V59)
        self.assertIn("CREATE TABLE contest_settlement_replay", migration)
        for column in (
            "from_contest_id",
            "operator_id",
            "reason",
            "status",
            "total_contests",
            "affected_users",
            "backup_json",
            "started_at",
            "finished_at",
            "error_message",
        ):
            with self.subTest(column=column):
                self.assertIn(column, migration)
        self.assertIn("RUNNING/COMPLETED/FAILED", migration)

    def test_v61_adds_restore_audit_columns_and_status(self):
        migration = read(MIGRATION_V61)
        for column in ("restored_by", "restored_at", "restore_reason"):
            with self.subTest(column=column):
                self.assertIn(column, migration)
        self.assertIn("RUNNING/COMPLETED/FAILED/RESTORED", migration)


if __name__ == "__main__":
    unittest.main()
