#!/usr/bin/env python3
"""Preserve the V63 failure matrix and exercise N+1 migration failure/recovery on disposable MySQL volumes.

The script intentionally runs both the packaged maintenance entry point and the
application's direct Spring/Flyway startup path. It only creates uniquely named
Compose projects and removes those projects and their volumes in a finally block.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
COMPOSE_FILE = ROOT / "tests/compose.yml"
IMAGE_JAR = "/app/app.jar"
MAINTENANCE_CLASS = "com.musealgo.oj.FlywayMigrationRunner"
MIGRATION_DIR = ROOT / "OJ-Backend/oj-admin/src/main/resources/db/migration"
LATEST_SCHEMA = max(int(path.name.split("__", 1)[0][1:]) for path in MIGRATION_DIR.glob("V*__*.sql"))
REPORT_MIGRATION = next(path for path in MIGRATION_DIR.glob("V*__report_job_lifecycle.sql"))
REPORT_SCHEMA_VERSION = int(REPORT_MIGRATION.name.split("__", 1)[0][1:])


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-image", required=True, help="candidate backend image containing the packaged migration code")
    parser.add_argument("--output", required=True, help="private directory for logs and JSON evidence")
    args = parser.parse_args()

    os.umask(0o077)
    output = Path(args.output).expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    output.chmod(0o700)
    evidence: dict[str, object] = {
        "started_at_utc": datetime.now(timezone.utc).isoformat(),
        "backend_image": args.backend_image,
        "compose_file": str(COMPOSE_FILE),
        "scenarios": {},
        "projects": [],
        "cleanup": [],
    }
    project_data: list[dict[str, object]] = []
    temporary_directory = tempfile.TemporaryDirectory(prefix="musealgo-flyway-failure-")
    temporary = Path(temporary_directory.name)
    command_index = 0
    env = os.environ.copy()
    for key in tuple(env):
        if key.startswith("OJ_CI_") or key in {
            "OJ_DB_HOST", "OJ_DB_PORT", "OJ_DB_USER", "OJ_DB_PASS", "OJ_DB_MIGRATION_PASS",
            "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
            "SPRING_FLYWAY_ENABLED", "SPRING_FLYWAY_TARGET", "SPRING_PROFILES_ACTIVE",
        }:
            env.pop(key, None)

    def run(label: str, command: list[str], *, project_env: dict[str, str] | None = None,
            input_text: str | None = None, timeout: int = 900) -> subprocess.CompletedProcess[str]:
        nonlocal command_index
        command_index += 1
        result = subprocess.run(
            command,
            cwd=ROOT,
            env={**env, **(project_env or {})},
            input=input_text,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
        log_path = output / f"{command_index:02d}-{label}.log"
        log_path.write_text(
            f"exit_code={result.returncode}\n--- stdout ---\n{result.stdout}\n--- stderr ---\n{result.stderr}",
            encoding="utf-8",
        )
        log_path.chmod(0o600)
        return result

    def compose(project: str, env_file: Path, *parts: str, timeout: int = 900) -> subprocess.CompletedProcess[str]:
        return run(
            f"{project}-{parts[0]}",
            ["docker", "compose", "--env-file", str(env_file), "-p", project, "-f", str(COMPOSE_FILE), *parts],
            timeout=timeout,
        )

    def create_project(label: str) -> tuple[str, Path, dict[str, str]]:
        project = f"musealgo-flyway-{label}-{secrets.token_hex(4)}"
        values = {
            "OJ_CI_DB_PASSWORD": secrets.token_hex(32),
            "OJ_CI_MQ_PASSWORD": secrets.token_hex(32),
            "OJ_CI_MYSQL_PORT": str(free_port()),
        }
        env_file = temporary / f"{project}.env"
        env_file.write_text("".join(f"{key}={value}\n" for key, value in values.items()), encoding="utf-8")
        env_file.chmod(0o600)
        record: dict[str, object] = {"project": project, "started": False, "env_file": str(env_file)}
        project_data.append(record)
        evidence["projects"].append(project)  # type: ignore[union-attr]
        return project, env_file, values

    def start_mysql(project: str, env_file: Path) -> None:
        started = compose(project, env_file, "up", "-d", "--wait", "mysql", timeout=900)
        if started.returncode:
            raise RuntimeError(f"MySQL startup failed for {project}; see {output}")
        next(record for record in project_data if record["project"] == project)["started"] = True

    def sql(project: str, env_file: Path, query: str, label: str) -> str:
        # Fixture SQL is captured only in a mode-0600 log.
        query_result = subprocess.run(
            ["docker", "compose", "--env-file", str(env_file), "-p", project, "-f", str(COMPOSE_FILE),
             "exec", "-T", "mysql", "sh", "-c",
             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names oj_db'],
            cwd=ROOT,
            env=env,
            input=query,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=120,
            check=False,
        )
        nonlocal command_index
        command_index += 1
        log_path = output / f"{command_index:02d}-{label}.log"
        log_path.write_text(
            f"exit_code={query_result.returncode}\n--- stdout ---\n{query_result.stdout}\n--- stderr ---\n{query_result.stderr}",
            encoding="utf-8",
        )
        log_path.chmod(0o600)
        if query_result.returncode:
            raise RuntimeError(f"SQL fixture failed for {project}; see {log_path}")
        return query_result.stdout.strip()

    def dump_database(project: str, env_file: Path, path: Path, label: str = "verified-v53-backup") -> str:
        nonlocal command_index
        result = subprocess.run(
            ["docker", "compose", "--env-file", str(env_file), "-p", project, "-f", str(COMPOSE_FILE),
             "exec", "-T", "mysql", "sh", "-c",
             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines '
             "--triggers --events --set-gtid-purged=OFF --no-tablespaces --databases oj_db"],
            cwd=ROOT,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=180,
            check=False,
        )
        command_index += 1
        log_path = output / f"{command_index:02d}-{label}.log"
        log_path.write_text(
            f"exit_code={result.returncode}\nbackup_bytes={len(result.stdout)}\n--- stderr ---\n"
            f"{result.stderr.decode(errors='replace')}",
            encoding="utf-8",
        )
        log_path.chmod(0o600)
        if result.returncode or not result.stdout:
            raise RuntimeError(f"V53 backup creation failed; see {log_path}")
        path.write_bytes(result.stdout)
        path.chmod(0o600)
        return hashlib.sha256(result.stdout).hexdigest()

    def restore_database(project: str, env_file: Path, dump_path: Path, expected_sha256: str,
                         label: str = "verified-v53-backup-restore") -> str:
        nonlocal command_index
        dump = dump_path.read_bytes()
        digest = hashlib.sha256(dump).hexdigest()
        if digest != expected_sha256:
            raise RuntimeError("Verified database backup SHA-256 changed before restore")
        result = subprocess.run(
            ["docker", "compose", "--env-file", str(env_file), "-p", project, "-f", str(COMPOSE_FILE),
             "exec", "-T", "mysql", "sh", "-c",
             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot'],
            cwd=ROOT,
            env=env,
            input=dump,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=300,
            check=False,
        )
        command_index += 1
        log_path = output / f"{command_index:02d}-{label}.log"
        log_path.write_text(
            f"exit_code={result.returncode}\nbackup_sha256={digest}\nbackup_bytes={len(dump)}\n"
            f"--- stdout ---\n{result.stdout.decode(errors='replace')}\n--- stderr ---\n"
            f"{result.stderr.decode(errors='replace')}",
            encoding="utf-8",
        )
        log_path.chmod(0o600)
        if result.returncode:
            raise RuntimeError(f"V53 backup restore failed; see {log_path}")
        return digest

    def history_summary(project: str, env_file: Path, label: str) -> dict[str, str]:
        return {
            "rows": sql(project, env_file, "SELECT COUNT(*) FROM flyway_schema_history;", f"{label}-history-count"),
            "current": sql(project, env_file,
                            "SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1;",
                            f"{label}-history-current"),
        }

    def maintenance(project: str, env_file: Path, password: str, operation: str, label: str) -> subprocess.CompletedProcess[str]:
        return run(
            f"{project}-{label}",
            ["docker", "run", "--rm", "--network", f"{project}_default",
             "--entrypoint", "java", "-e", "OJ_DB_HOST=mysql", "-e", f"OJ_DB_MIGRATION_PASS={password}",
             args.backend_image, "-cp", IMAGE_JAR, f"-Dloader.main={MAINTENANCE_CLASS}",
             "org.springframework.boot.loader.launch.PropertiesLauncher", operation],
            timeout=900,
        )

    def direct_spring_migrate(project: str, password: str, label: str,
                              target_version: str | None = None) -> subprocess.CompletedProcess[str]:
        command = ["docker", "run", "--rm", "--network", f"{project}_default",
                   "--entrypoint", "java", "-e", "OJ_DB_HOST=mysql", "-e", "OJ_DB_USER=musealgo_migrator",
                   "-e", f"OJ_DB_PASS={password}", "-e", "OJ_FLYWAY_ENABLED=true",
                   "-e", "JWT_SECRET=" + secrets.token_hex(48)]
        if target_version is not None:
            command.extend(["-e", f"SPRING_FLYWAY_TARGET={target_version}"])
        command.extend([args.backend_image, "-Xmx512m", "-Dspring.main.web-application-type=none",
                        "-jar", IMAGE_JAR])
        return run(
            f"{project}-{label}",
            command,
            timeout=900,
        )

    def create_migrator(project: str, env_file: Path, label: str) -> str:
        password = secrets.token_hex(32)
        sql(project, env_file,
            f"CREATE USER 'musealgo_migrator'@'%' IDENTIFIED BY '{password}'; "
            "GRANT ALL PRIVILEGES ON oj_db.* TO 'musealgo_migrator'@'%'; FLUSH PRIVILEGES;",
            label)
        return password

    def image_id() -> str:
        result = run("backend-image-inspect", ["docker", "image", "inspect", args.backend_image, "--format", "{{.Id}}"], timeout=60)
        if result.returncode:
            raise RuntimeError("Backend image is unavailable; see the image-inspect log")
        return result.stdout.strip()

    failure: str | None = None
    try:
        evidence["backend_image_id"] = image_id()
        failure_project, failure_env, _ = create_project("failure")
        start_mysql(failure_project, failure_env)
        migrator_password = secrets.token_hex(32)
        sql(
            failure_project,
            failure_env,
            f"INSERT INTO user(id,username,password,nickname,role) VALUES "
            f"(9000001,'db03_marker_{secrets.token_hex(4)}','synthetic','pre-failure marker',1); "
            "INSERT INTO sys_user_role(user_id,role_id) VALUES (9000001,1);",
            "pre-failure-marker-fixture",
        )
        marker_username = sql(
            failure_project, failure_env,
            "SELECT username FROM user WHERE id=9000001;",
            "pre-failure-marker-check",
        )
        backup_path = output / "v53-before-failure.sql"
        backup_sha256 = dump_database(failure_project, failure_env, backup_path)
        sql(
            failure_project,
            failure_env,
            f"CREATE USER 'musealgo_migrator'@'%' IDENTIFIED BY '{migrator_password}'; "
            "GRANT ALL PRIVILEGES ON oj_db.* TO 'musealgo_migrator'@'%'; FLUSH PRIVILEGES;",
            "failure-migrator-fixture",
        )
        history_before = sql(
            failure_project, failure_env,
            "SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1;",
            "failure-history-before",
        )
        assert history_before == "53", f"Expected V53 baseline snapshot before preflight, got {history_before}"

        # Supported-history guard: a future recorded version must fail read-only.
        history_at_baseline = history_summary(failure_project, failure_env, "future-history-before")
        sql(
            failure_project,
            failure_env,
            "INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,installed_by,"
            "execution_time,success) SELECT COALESCE(MAX(installed_rank),0)+1,'999','future fixture','SQL',"
            "'V999__fixture.sql',CURRENT_USER(),0,1 FROM flyway_schema_history;",
            "future-history-fixture",
        )
        future_status = maintenance(failure_project, failure_env, migrator_password, "status", "future-history-status")
        history_after_future = history_summary(failure_project, failure_env, "future-history-after")
        future_rows = sql(
            failure_project, failure_env,
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version='999' AND success=1;",
            "future-history-row-count",
        )
        assert future_status.returncode != 0 and future_rows == "1", "status must reject a future history row"
        assert history_after_future["rows"] == str(int(history_at_baseline["rows"]) + 1), (
            "future-history preflight unexpectedly modified schema history"
        )
        sql(failure_project, failure_env, "DELETE FROM flyway_schema_history WHERE version='999';", "future-history-fixture-cleanup")
        assert history_summary(failure_project, failure_env, "future-history-restored") == history_at_baseline

        # Non-empty schema without the expected history table must remain untouched.
        table_count_before_rename = sql(
            failure_project, failure_env,
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='oj_db';",
            "missing-history-table-count-before",
        )
        sql(failure_project, failure_env,
            "RENAME TABLE flyway_schema_history TO flyway_schema_history_missing_fixture;",
            "missing-history-rename")
        no_history_status = maintenance(failure_project, failure_env, migrator_password, "status", "missing-history-status")
        original_history_tables = sql(
            failure_project, failure_env,
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='oj_db' AND table_name='flyway_schema_history';",
            "missing-history-original-count",
        )
        renamed_history_tables = sql(
            failure_project, failure_env,
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='oj_db' "
            "AND table_name='flyway_schema_history_missing_fixture';",
            "missing-history-renamed-count",
        )
        table_count_after_rename = sql(
            failure_project, failure_env,
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='oj_db';",
            "missing-history-table-count-after",
        )
        assert no_history_status.returncode != 0 and original_history_tables == "0" and renamed_history_tables == "1"
        assert table_count_after_rename == table_count_before_rename, "missing-history status created or removed tables"
        sql(failure_project, failure_env,
            "RENAME TABLE flyway_schema_history_missing_fixture TO flyway_schema_history;",
            "missing-history-restore")

        # A truly empty oj_db schema must not be baselined or populated by status.
        tables = sql(failure_project, failure_env, "SHOW TABLES;", "empty-schema-table-list").splitlines()
        assert "flyway_schema_history" in tables and len(tables) > 0
        drop_tables = "SET FOREIGN_KEY_CHECKS=0; " + " ".join(
            f"DROP TABLE `{table.replace('`', '``')}`;" for table in tables
        ) + " SET FOREIGN_KEY_CHECKS=1;"
        sql(failure_project, failure_env, drop_tables, "empty-schema-drop-fixture")
        tables_before_empty_status = sql(
            failure_project, failure_env,
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='oj_db';",
            "empty-schema-count-before-status",
        )
        empty_status = maintenance(failure_project, failure_env, migrator_password, "status", "empty-schema-status")
        tables_after_empty_status = sql(
            failure_project, failure_env,
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='oj_db';",
            "empty-schema-count-after-status",
        )
        assert empty_status.returncode != 0 and tables_before_empty_status == "0" and tables_after_empty_status == "0", (
            "status must reject a truly empty schema without creating a baseline or tables"
        )
        empty_restore_sha = restore_database(failure_project, failure_env, backup_path, backup_sha256)
        assert empty_restore_sha == backup_sha256
        assert history_summary(failure_project, failure_env, "empty-schema-restored")["current"] == "53"
        assert sql(failure_project, failure_env, "SELECT COUNT(*) FROM user WHERE id=9000001;", "empty-schema-marker-restored") == "1"
        evidence["scenarios"]["history_floor_and_empty_schema_guards"] = {
            "future_version_status_rejected_readonly": True,
            "future_history_before": history_at_baseline,
            "future_history_after_status": history_after_future,
            "future_history_restored_after_fixture": True,
            "nonempty_missing_history_rejected_readonly": True,
            "table_count_before_and_after_missing_history_status": [int(table_count_before_rename), int(table_count_after_rename)],
            "missing_history_not_recreated": original_history_tables == "0",
            "empty_schema_status_rejected_readonly": True,
            "empty_schema_table_count_before_after_status": [int(tables_before_empty_status), int(tables_after_empty_status)],
            "restored_from_verified_v53_backup_sha256": empty_restore_sha,
            "marker_restored": True,
        }

        sql(
            failure_project,
            failure_env,
            "DELETE FROM sys_user_role; DELETE FROM sys_role_permission; DELETE FROM sys_role; "
            "INSERT INTO sys_role(id,code,name) VALUES (1,'OWNER','unknown role'); "
            "FLUSH PRIVILEGES;",
            "failure-role-corruption-fixture",
        )
        preflight = maintenance(failure_project, failure_env, migrator_password, "status", "preflight-status")
        routine_before = sql(
                failure_project, failure_env,
                "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='oj_db' "
                "AND routine_name='bootstrap_reference_roles' AND routine_type='PROCEDURE';",
                "routine-before",
        )
        preflight_text = preflight.stdout + preflight.stderr
        assert preflight.returncode != 0 and "映射" in preflight_text, "status must reject unknown roles before DDL"
        assert routine_before == "0", f"read-only status left routine residue: {routine_before}"
        history_after_preflight = sql(
                failure_project, failure_env,
                "SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1;",
                "preflight-history-after",
        )
        assert history_after_preflight == history_before, "status changed Flyway history"
        evidence["scenarios"]["readonly_unknown_role_preflight"] = {
                "rejected": True,
                "history_current_before_after": [history_before, history_after_preflight],
                "routine_count_after": int(routine_before),
                "verified_backup_sha256": backup_sha256,
                "backup_bytes": backup_path.stat().st_size,
                "marker_username": marker_username,
        }

        direct = direct_spring_migrate(failure_project, migrator_password, "direct-spring-migrate")
        direct_text = direct.stdout + direct.stderr
        assert direct.returncode != 0, "direct Spring Flyway startup unexpectedly succeeded with an unknown role"
        assert "V63" in direct_text or "内置角色" in direct_text or "角色映射" in direct_text, (
                "direct Spring startup failed before reaching the intended V63 role validation; see its log"
        )
        routine_after_failure = sql(
                failure_project, failure_env,
                "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='oj_db' "
                "AND routine_name='bootstrap_reference_roles' AND routine_type='PROCEDURE';",
                "routine-after-failure",
        )
        failed_v63 = sql(
                failure_project, failure_env,
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='63' AND success=0;",
                "failed-v63-history",
        )
        successful_v63 = sql(
                failure_project, failure_env,
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='63' AND success=1;",
                "successful-v63-history",
        )
        current_after_failure = sql(
                failure_project, failure_env,
                "SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1;",
                "failure-history-after",
        )
        assert routine_after_failure == "1", f"expected observable routine residue after V63 SQL failure; got {routine_after_failure}"
        assert int(failed_v63) >= 1, f"expected Flyway failed V63 record; got {failed_v63}"
        assert successful_v63 == "0" and current_after_failure == "62", (
                f"failed V63 was recorded as successful/current: success={successful_v63}, current={current_after_failure}"
        )

        retry = maintenance(failure_project, failure_env, migrator_password, "migrate", "maintenance-retry")
        retry_history = sql(
                failure_project, failure_env,
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='63' AND success=1;",
                "retry-successful-v63-history",
        )
        retry_routine = sql(
                failure_project, failure_env,
                "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='oj_db' "
                "AND routine_name='bootstrap_reference_roles' AND routine_type='PROCEDURE';",
                "retry-routine",
        )
        assert retry.returncode != 0, "maintenance migrate must refuse failed history without repair"
        assert retry_history == "0" and retry_routine == "1", "retry modified failed migration state"
        evidence["scenarios"]["direct_flyway_failure"] = {
                "direct_startup_rejected": True,
                "success_version_before_failure": int(current_after_failure),
                "failed_v63_history_rows": int(failed_v63),
                "successful_v63_history_rows": int(successful_v63),
                "routine_residue_after_failure": int(routine_after_failure),
                "maintenance_retry_refused_without_repair": True,
                "routine_residue_after_retry": int(retry_routine),
                "log": "direct Spring startup and maintenance retry logs are retained in this evidence directory",
        }

        recovery_project, recovery_env, _ = create_project("backup-recovery")
        start_mysql(recovery_project, recovery_env)
        restored_sha256 = restore_database(recovery_project, recovery_env, backup_path, backup_sha256)
        assert restored_sha256 == backup_sha256
        restored_history = sql(
            recovery_project, recovery_env,
            "SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1;",
            "restored-history-before-migrate",
        )
        restored_marker = sql(
            recovery_project, recovery_env,
            "SELECT COUNT(*) FROM user WHERE id=9000001 AND username='" + marker_username + "';",
            "restored-marker-before-migrate",
        )
        assert restored_history == "53" and restored_marker == "1", (
            f"backup restore did not reproduce the V53 marker state: history={restored_history}, marker={restored_marker}"
        )
        recovery_password = secrets.token_hex(32)
        sql(
            recovery_project,
            recovery_env,
            f"CREATE USER 'musealgo_migrator'@'%' IDENTIFIED BY '{recovery_password}'; "
            "GRANT ALL PRIVILEGES ON oj_db.* TO 'musealgo_migrator'@'%'; FLUSH PRIVILEGES;",
            "backup-recovery-migrator-fixture",
        )
        migrated = maintenance(recovery_project, recovery_env, recovery_password, "migrate", "backup-recovery-migrate")
        assert migrated.returncode == 0 and f'"current":"{LATEST_SCHEMA}"' in migrated.stdout, (
            "restored backup could not be migrated to the current schema; see backup-recovery-migrate log"
        )
        recovery_routine = sql(
                recovery_project, recovery_env,
                "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='oj_db' "
                "AND routine_name='bootstrap_reference_roles' AND routine_type='PROCEDURE';",
                "recovery-routine",
        )
        recovery_failed = sql(
                recovery_project, recovery_env,
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='63' AND success=0;",
                "recovery-failed-history",
        )
        recovery_roles = sql(
                recovery_project, recovery_env,
                "SELECT GROUP_CONCAT(CONCAT(id,':',code) ORDER BY id) FROM sys_role WHERE id IN (1,2,3);",
                "recovery-role-layout",
        )
        demo_users = sql(recovery_project, recovery_env, "SELECT COUNT(*) FROM user;", "recovery-demo-users")
        assert recovery_routine == "0" and recovery_failed == "0", "successful recovery left routine or failed history residue"
        assert recovery_roles == "1:STUDENT,2:TEACHER,3:ADMIN" and demo_users == "1", (
            f"unexpected recovered roles/users: {recovery_roles}, users={demo_users}"
        )
        restored_marker_after = sql(
            recovery_project, recovery_env,
            "SELECT COUNT(*) FROM user WHERE id=9000001 AND username='" + marker_username + "' "
            "AND role=1 AND nickname='pre-failure marker';",
            "backup-recovery-marker-after",
        )
        restored_association = sql(
            recovery_project, recovery_env,
            "SELECT COUNT(*) FROM sys_user_role WHERE user_id=9000001 AND role_id=1;",
            "backup-recovery-marker-role",
        )
        assert restored_marker_after == "1" and restored_association == "1", "recovery lost marker user or role association"
        evidence["scenarios"]["verified_backup_restore_after_failure"] = {
            "backup_created_before_corruption_and_failure": True,
            "backup_sha256": backup_sha256,
            "backup_bytes": backup_path.stat().st_size,
            "restored_sha256_verified_before_import": restored_sha256,
            "restored_history_before_migrate": int(restored_history),
            "migrated_current": LATEST_SCHEMA,
            "marker_preserved": restored_marker_after == "1",
            "marker_role_association_preserved": restored_association == "1",
            "roles": recovery_roles,
            "users_including_synthetic_marker": int(demo_users),
            "routine_count": int(recovery_routine),
            "failed_v63_rows": int(recovery_failed),
        }

        # Preserve the V63 failure scenario above. Separately build a clean V63 backup,
        # force the new report migration to fail after its additive ALTER, then recover only
        # by restoring that clean backup and applying the complete current migration set.
        # The report lifecycle is intentionally split across V64 (columns + tables), V65 (indexes)
        # and V66 (batched backfill); only the first of them may be the N+1 failure target.
        if REPORT_SCHEMA_VERSION > LATEST_SCHEMA or REPORT_SCHEMA_VERSION <= 63:
            raise RuntimeError("the report lifecycle migration set is not ahead of V63")

        v63_source, v63_source_env, _ = create_project("v63-source")
        start_mysql(v63_source, v63_source_env)
        v63_source_sha = restore_database(v63_source, v63_source_env, backup_path, backup_sha256,
                                          "v63-source-restore-v53")
        v63_source_password = create_migrator(v63_source, v63_source_env, "v63-source-migrator")
        v63_result = direct_spring_migrate(v63_source, v63_source_password, "v63-source-migrate",
                                           target_version=str(REPORT_SCHEMA_VERSION - 1))
        v63_current = sql(v63_source, v63_source_env,
                          "SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1;",
                          "v63-source-current")
        # Flyway 在上下文启动前已提交到目标版本；无 Web 上下文时 Web 文档自动配置可能
        # 在迁移完成后失败，因此该夹具以数据库历史为准，不要求进程以 0 退出。
        assert v63_current == str(REPORT_SCHEMA_VERSION - 1), (
            "could not create the clean pre-N+1 migration fixture; see v63-source-migrate log"
        )
        # Legacy rows that predate the new columns: the backfill must bind them by requester_id
        # without inventing a value when requester_id itself is NULL.
        sql(v63_source, v63_source_env,
            "INSERT INTO report_job(student_id,class_id,period_start,period_end,status,requester_id) VALUES "
            "(9000001,9000001,'2026-10-05','2026-10-11','PENDING',4242),"
            "(9000002,9000001,'2026-10-05','2026-10-11','PENDING',NULL),"
            "(9000003,9000001,'2026-10-05','2026-10-11','PENDING',7777);",
            "pre-migration-legacy-report-rows")
        v63_backup = output / f"v{REPORT_SCHEMA_VERSION - 1}-before-n-plus-one.sql"
        v63_backup_sha = dump_database(v63_source, v63_source_env, v63_backup,
                                       "pre-n-plus-one-verified-backup")

        n_plus_one_failure, n_plus_one_failure_env, _ = create_project("n-plus-one-failure")
        start_mysql(n_plus_one_failure, n_plus_one_failure_env)
        restore_database(n_plus_one_failure, n_plus_one_failure_env, v63_backup, v63_backup_sha,
                         "n-plus-one-failure-restore")
        n_plus_one_password = create_migrator(n_plus_one_failure, n_plus_one_failure_env,
                                               "n-plus-one-failure-migrator")
        sql(n_plus_one_failure, n_plus_one_failure_env,
            "CREATE TABLE report_job_attempt (synthetic_conflict INT NOT NULL);",
            "n-plus-one-conflict-fixture")
        history_before_n_plus_one = sql(
            n_plus_one_failure, n_plus_one_failure_env,
            f"SELECT GROUP_CONCAT(CONCAT(version,':',checksum,':',success) ORDER BY installed_rank SEPARATOR ',') "
            f"FROM flyway_schema_history WHERE version IS NULL OR CAST(version AS UNSIGNED)<{REPORT_SCHEMA_VERSION};",
            "n-plus-one-history-before",
        )
        failed_n_plus_one = direct_spring_migrate(n_plus_one_failure, n_plus_one_password,
                                                  "n-plus-one-direct-failure")
        failed_n_plus_one_text = failed_n_plus_one.stdout + failed_n_plus_one.stderr
        history_after_n_plus_one = sql(
            n_plus_one_failure, n_plus_one_failure_env,
            f"SELECT GROUP_CONCAT(CONCAT(version,':',checksum,':',success) ORDER BY installed_rank SEPARATOR ',') "
            f"FROM flyway_schema_history WHERE version IS NULL OR CAST(version AS UNSIGNED)<{REPORT_SCHEMA_VERSION};",
            "n-plus-one-history-after",
        )
        n_plus_one_current = sql(
            n_plus_one_failure, n_plus_one_failure_env,
            "SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1;",
            "n-plus-one-current-after-failure",
        )
        n_plus_one_failure_rows = sql(
            n_plus_one_failure, n_plus_one_failure_env,
            f"SELECT COUNT(*) FROM flyway_schema_history WHERE version='{REPORT_SCHEMA_VERSION}' AND success=0;",
            "n-plus-one-failed-history",
        )
        n_plus_one_alter = sql(
            n_plus_one_failure, n_plus_one_failure_env,
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='oj_db' AND table_name='report_job' "
            "AND column_name IN ('active_requester_id','generation_no','terminal_revision','snapshot_id',"
            "'lease_owner','lease_until','heartbeat_at','next_attempt_at','last_error_code');",
            "n-plus-one-partial-alter",
        )
        assert failed_n_plus_one.returncode != 0 and "report_job_attempt" in failed_n_plus_one_text, (
            "the intended report migration conflict did not fail at the approved N+1 table; see direct-failure log"
        )
        assert history_before_n_plus_one == history_after_n_plus_one, "N+1 failure modified frozen migration history"
        assert n_plus_one_current == str(REPORT_SCHEMA_VERSION - 1) and n_plus_one_failure_rows == "1", (
            f"N+1 failure state is unexpected: current={n_plus_one_current}, failed={n_plus_one_failure_rows}"
        )
        assert n_plus_one_alter == "9", f"expected additive report_job DDL residue, got {n_plus_one_alter} columns"
        failed_backfill = sql(
            n_plus_one_failure, n_plus_one_failure_env,
            "SELECT GROUP_CONCAT(CONCAT(student_id-9000000,':',COALESCE(active_requester_id,-1)) "
            "ORDER BY student_id) FROM report_job WHERE student_id BETWEEN 9000001 AND 9000003;",
            "n-plus-one-failed-backfill-state",
        )
        assert failed_backfill == "1:-1,2:-1,3:-1", (
            f"a failed V64 must not backfill requester rows, got {failed_backfill}"
        )

        # Documented manual repair path: validate must reject the failed row, then an operator
        # removes the failed history entry, the conflict fixture and the additive residue before
        # re-running. This is the only in-place alternative to restoring the verified backup.
        repair_status = maintenance(n_plus_one_failure, n_plus_one_failure_env, n_plus_one_password,
                                    "status", "n-plus-one-repair-status")
        repair_status_text = (repair_status.stdout + repair_status.stderr).lower()
        assert repair_status.returncode != 0 and "failed" in repair_status_text, (
            "status must refuse a database with a failed report migration; see n-plus-one-repair-status log"
        )
        sql(
            n_plus_one_failure, n_plus_one_failure_env,
            f"DELETE FROM flyway_schema_history WHERE version='{REPORT_SCHEMA_VERSION}' AND success=0;"
            "DROP TABLE report_job_attempt;"
            "ALTER TABLE report_job "
            "DROP COLUMN active_requester_id, DROP COLUMN generation_no, DROP COLUMN terminal_revision, "
            "DROP COLUMN snapshot_id, DROP COLUMN lease_owner, DROP COLUMN lease_until, "
            "DROP COLUMN heartbeat_at, DROP COLUMN next_attempt_at, DROP COLUMN last_error_code;",
            "n-plus-one-manual-repair-cleanup",
        )
        # Recovery uses the same standalone maintenance entry as the deployed migrate service.
        repaired = maintenance(n_plus_one_failure, n_plus_one_failure_env, n_plus_one_password,
                               "migrate", "n-plus-one-manual-repair-migrate")
        assert repaired.returncode == 0, (
            f"manual repair migration process exited {repaired.returncode}; "
            "see n-plus-one-manual-repair-migrate log"
        )
        repaired_current = sql(
            n_plus_one_failure, n_plus_one_failure_env,
            "SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1;",
            "n-plus-one-repair-current",
        )
        repaired_backfill = sql(
            n_plus_one_failure, n_plus_one_failure_env,
            "SELECT GROUP_CONCAT(CONCAT(student_id-9000000,':',COALESCE(active_requester_id,-1)) "
            "ORDER BY student_id) FROM report_job WHERE student_id BETWEEN 9000001 AND 9000003;",
            "n-plus-one-repair-backfill",
        )
        repaired_failed_rows = sql(
            n_plus_one_failure, n_plus_one_failure_env,
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success=0;",
            "n-plus-one-repair-failed-history",
        )
        assert repaired_current == str(LATEST_SCHEMA) and repaired_failed_rows == "0", (
            f"manual repair did not reach the current schema: current={repaired_current}, "
            f"failed={repaired_failed_rows}; see n-plus-one-manual-repair-migrate log"
        )
        assert repaired_backfill == "1:4242,2:-1,3:7777", (
            f"batched backfill did not bind legacy requesters correctly, got {repaired_backfill}"
        )

        n_plus_one_recovery, n_plus_one_recovery_env, _ = create_project("n-plus-one-recovery")
        start_mysql(n_plus_one_recovery, n_plus_one_recovery_env)
        restore_database(n_plus_one_recovery, n_plus_one_recovery_env, v63_backup, v63_backup_sha,
                         "n-plus-one-recovery-restore")
        n_plus_one_recovery_password = create_migrator(
            n_plus_one_recovery, n_plus_one_recovery_env, "n-plus-one-recovery-migrator")
        recovered_n_plus_one = maintenance(n_plus_one_recovery, n_plus_one_recovery_env,
                                          n_plus_one_recovery_password, "migrate", "n-plus-one-clean-recovery")
        assert recovered_n_plus_one.returncode == 0, (
            f"clean recovery migration process exited {recovered_n_plus_one.returncode}; "
            "see n-plus-one-clean-recovery log"
        )
        recovered_current = sql(
            n_plus_one_recovery, n_plus_one_recovery_env,
            "SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1;",
            "n-plus-one-recovery-current",
        )
        recovered_failed_rows = sql(
            n_plus_one_recovery, n_plus_one_recovery_env,
            f"SELECT COUNT(*) FROM flyway_schema_history WHERE version='{REPORT_SCHEMA_VERSION}' AND success=0;",
            "n-plus-one-recovery-failed-history",
        )
        recovered_report_tables = sql(
            n_plus_one_recovery, n_plus_one_recovery_env,
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='oj_db' "
            "AND table_name IN ('report_job_attempt','report_notification_intent');",
            "n-plus-one-recovery-tables",
        )
        recovered_source_error = sql(
            n_plus_one_recovery, n_plus_one_recovery_env,
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='oj_db' "
            "AND table_name='report_notification_intent' AND column_name='source_error_code';",
            "n-plus-one-recovery-source-error",
        )
        # Both the maintenance process and the recovered database state are required to succeed.
        assert recovered_current == str(LATEST_SCHEMA), (
            "clean backup recovery did not reach current schema; see n-plus-one-clean-recovery log"
        )
        assert recovered_failed_rows == "0" and recovered_report_tables == "2" and recovered_source_error == "1", (
            "clean N+1 recovery is missing approved report/outbox schema or left failed history"
        )
        recovered_backfill = sql(
            n_plus_one_recovery, n_plus_one_recovery_env,
            "SELECT GROUP_CONCAT(CONCAT(student_id-9000000,':',COALESCE(active_requester_id,-1)) "
            "ORDER BY student_id) FROM report_job WHERE student_id BETWEEN 9000001 AND 9000003;",
            "n-plus-one-recovery-backfill",
        )
        assert recovered_backfill == "1:4242,2:-1,3:7777", (
            f"batched backfill did not bind legacy requesters correctly, got {recovered_backfill}"
        )
        evidence["scenarios"]["n_plus_one_report_migration_failure_and_clean_restore"] = {
            "migration_version": REPORT_SCHEMA_VERSION,
            "prior_current": REPORT_SCHEMA_VERSION - 1,
            "source_backup_sha256": v63_backup_sha,
            "failure_exit_code": failed_n_plus_one.returncode,
            "failed_history_rows": int(n_plus_one_failure_rows),
            "prior_history_unchanged": history_before_n_plus_one == history_after_n_plus_one,
            "expected_mysql_ddl_residue_columns": int(n_plus_one_alter),
            "clean_restore_exit_code": recovered_n_plus_one.returncode,
            "recovered_current": int(recovered_current),
            "recovered_failed_rows": int(recovered_failed_rows),
            "report_tables": int(recovered_report_tables),
            "source_error_code_column": int(recovered_source_error),
            "failed_state_backfill": failed_backfill,
            "manual_repair_status_exit": repair_status.returncode,
            "manual_repair_exit": repaired.returncode,
            "manual_repair_current": int(repaired_current),
            "manual_repair_failed_rows": int(repaired_failed_rows),
            "manual_repair_backfill": repaired_backfill,
            "clean_restore_backfill": recovered_backfill,
            "latest_schema": LATEST_SCHEMA,
        }
    except Exception as exc:  # retain partial matrix evidence and logs before returning failure
        failure = f"{type(exc).__name__}: {exc}"
        evidence["failure"] = failure
    finally:
        for record in reversed(project_data):
            project = str(record["project"])
            env_file = Path(str(record["env_file"]))
            if env_file.exists():
                result = run(
                    f"{project}-cleanup",
                    ["docker", "compose", "--env-file", str(env_file), "-p", project,
                     "-f", str(COMPOSE_FILE), "down", "-v", "--remove-orphans"],
                    timeout=300,
                )
                cleanup_result = {"project": project, "exit_code": result.returncode, "volumes_removed": result.returncode == 0}
            else:
                cleanup_result = {"project": project, "exit_code": None, "volumes_removed": False,
                                  "reason": "project startup did not complete; inspect Docker if partial resources were created"}
            evidence["cleanup"].append(cleanup_result)  # type: ignore[union-attr]
        evidence["finished_at_utc"] = datetime.now(timezone.utc).isoformat()
        evidence_path = output / "flyway-failure-smoke.json"
        evidence_path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        evidence_path.chmod(0o600)
        temporary_directory.cleanup()

    if failure:
        print(f"Flyway failure smoke failed: {failure}", file=sys.stderr)
        print(f"Evidence: {output / 'flyway-failure-smoke.json'}", file=sys.stderr)
        return 1
    print(json.dumps(evidence, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
