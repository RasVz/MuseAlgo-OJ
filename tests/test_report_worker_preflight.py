import importlib.util
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "report_worker_preflight", ROOT / "scripts/report_worker_preflight.py")
PREFLIGHT = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = PREFLIGHT
SPEC.loader.exec_module(PREFLIGHT)


def backend(environment=None, *, name="backend", command=None, deploy=None, image="musealgo/backend:latest"):
    service = {"image": image, "environment": environment or {}}
    if command is not None:
        service["command"] = command
    if deploy is not None:
        service["deploy"] = deploy
    return {"services": {name: service}}


class ReportWorkerPreflightTest(unittest.TestCase):
    def evaluate(self, documents, scales, maintenance=False, legacy_recovery_acknowledged=False):
        with tempfile.TemporaryDirectory() as folder:
            paths = []
            for index, document in enumerate(documents):
                path = Path(folder) / f"compose-{index}.json"
                path.write_text(json.dumps(document), encoding="utf-8")
                paths.append(path)
            return PREFLIGHT.evaluate(paths, PREFLIGHT._scale_overrides(scales), maintenance,
                                      legacy_recovery_acknowledged)

    def test_main_backend_and_same_image_flyway_only_service_count_one(self):
        document = backend()
        document["services"]["migrate"] = {
            "image": "musealgo/backend:latest",
            "entrypoint": ["java", "-Dloader.main=com.musealgo.oj.FlywayMigrationRunner", "org.springframework.boot.loader.launch.PropertiesLauncher"],
        }
        result = self.evaluate([document], ["backend=1"])
        self.assertEqual(1, result["report_generation_worker_instances"])
        self.assertEqual(1, result["report_notification_worker_instances"])
        self.assertEqual(["backend"], [item["service"] for item in result["services"]])

    def test_main_worker_plus_judge_only_profile_has_one_report_worker(self):
        document = backend()
        document["services"]["judge-backend"] = {
            "image": "musealgo/backend:latest",
            "environment": {"SPRING_PROFILES_ACTIVE": "worker"},
        }
        result = self.evaluate([document], ["backend=1", "judge-backend=1"])
        self.assertEqual(1, result["report_generation_worker_instances"])
        self.assertEqual([True, False], [item["report_worker_enabled"] for item in result["services"]])

    def test_rejects_scaled_two_replicas_and_two_backend_services(self):
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "more than one"):
            self.evaluate([backend()], ["backend=2"])
        second = backend(name="api")
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "more than one"):
            self.evaluate([backend(), second], ["backend=1", "api=1"])

    def test_maintenance_requires_explicit_zero_worker_override(self):
        stopped = backend({"OJ_REPORT_WORKER_ENABLED": "false"})
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "--maintenance"):
            self.evaluate([stopped], ["backend=1"])
        result = self.evaluate([stopped], ["backend=1"], maintenance=True)
        self.assertEqual(0, result["report_generation_worker_instances"])
        self.assertEqual("maintenance", result["mode"])

    def test_rejects_conflicting_environment_json_and_java_property_overrides(self):
        conflicting_json = backend({
            "OJ_REPORT_WORKER_ENABLED": "false",
            "SPRING_APPLICATION_JSON": '{"report":{"worker":{"enabled":true}}}',
        })
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "conflicting"):
            self.evaluate([conflicting_json], ["backend=1"])
        conflicting_java = backend({
            "OJ_REPORT_WORKER_ENABLED": "true",
            "JAVA_TOOL_OPTIONS": "-Dreport.worker.enabled=false",
        })
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "conflicting"):
            self.evaluate([conflicting_java], ["backend=1"])
        for overrides in (
                {"SPRING_APPLICATION_JSON": '{"report":{"worker":{"enabled":false}},"report.worker.enabled":true}'},
                {"JAVA_TOOL_OPTIONS": "-DOJ_REPORT_WORKER_ENABLED=true"},
                {"SPRING_APPLICATION_JSON": '{"OJ_REPORT_WORKER_ENABLED":true}'},
        ):
            document = backend({"OJ_REPORT_WORKER_ENABLED": "false", **overrides})
            with self.subTest(overrides=overrides), self.assertRaisesRegex(PREFLIGHT.PreflightError, "conflicting"):
                self.evaluate([document], ["backend=1"])

    def test_reads_split_json_command_override_and_simple_shell_launcher(self):
        split_json = backend(command=["java", "-jar", "app.jar", "--spring.application.json",
                                      '{"report":{"worker":{"enabled":false}}}'])
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "zero report-generation workers"):
            self.evaluate([split_json], ["backend=1"])
        shell = backend(command=["/bin/sh", "-c", "exec java -Dreport.worker.enabled=false -jar app.jar"])
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "zero report-generation workers"):
            self.evaluate([shell], ["backend=1"])
        opaque_shell = backend(command=["/bin/sh", "-c", "exec java $JAVA_OPTS -jar app.jar"])
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "opaque shell command"):
            self.evaluate([opaque_shell], ["backend=1"])

    def test_stdout_reports_counts_without_echoing_compose_credentials(self):
        document = backend({"OJ_DB_PASS": "synthetic-secret", "OJ_REPORT_WORKER_ENABLED": "true"})
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "compose.json"
            path.write_text(json.dumps(document), encoding="utf-8")
            stdout = io.StringIO()
            with patch.object(sys, "argv", ["report_worker_preflight", "--compose-json", str(path),
                                              "--scale", "backend=1"]), redirect_stdout(stdout):
                self.assertEqual(0, PREFLIGHT.main())
            rendered = stdout.getvalue()
            self.assertIn('"report_generation_worker_instances": 1', rendered)
            self.assertNotIn("synthetic-secret", rendered)

    def test_rejects_unresolved_flags_unknown_profiles_and_uninspected_configs(self):
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "unresolved"):
            self.evaluate([backend({"OJ_REPORT_WORKER_ENABLED": "${MISSING}"})], ["backend=1"])
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "uninspected profiles"):
            self.evaluate([backend({"SPRING_PROFILES_ACTIVE": "mystery"})], ["backend=1"])
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "SPRING_CONFIG_ADDITIONAL_LOCATION"):
            self.evaluate([backend({"SPRING_CONFIG_ADDITIONAL_LOCATION": "file:/etc/oj/"})], ["backend=1"])
        for raw in ('{"spring":{"config.location":"file:/etc/oj/"}}',
                    '{"spring.config":{"location":"file:/etc/oj/"}}'):
            with self.subTest(raw=raw), self.assertRaisesRegex(PREFLIGHT.PreflightError, "uninspected Spring config"):
                self.evaluate([backend({"SPRING_APPLICATION_JSON": raw})], ["backend=1"])
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "mounted Spring config"):
            document = backend()
            document["services"]["backend"]["volumes"] = [
                {"type": "bind", "source": "./application.yml", "target": "/app/config/application.yml"}]
            self.evaluate([document], ["backend=1"])
        for volume in (
                {"type": "bind", "source": "./report-config", "target": "/app/config/report"},
                {"type": "bind", "source": "./application-root", "target": "/app"},
                "./report-config:/app/config/report:ro",
                {"type": "bind", "source": "./custom-root", "target": "/custom"},
        ):
            document = backend()
            document["services"]["backend"]["volumes"] = [volume]
            if isinstance(volume, dict) and volume["target"] == "/custom":
                document["services"]["backend"]["working_dir"] = "/custom/app"
            with self.subTest(volume=volume), self.assertRaisesRegex(PREFLIGHT.PreflightError, "mounted Spring config"):
                self.evaluate([document], ["backend=1"])
        inherited = backend()
        inherited["services"]["backend"]["volumes_from"] = ["another-service"]
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "inherited volumes"):
            self.evaluate([inherited], ["backend=1"])

    def test_legacy_recovery_requires_operator_acknowledgement(self):
        document = backend({"OJ_REPORT_WORKER_ENABLED": "true",
                            "OJ_REPORT_WORKER_RECOVER_LEGACY": "true"})
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "legacy report recovery"):
            self.evaluate([document], ["backend=1"])
        acknowledged = self.evaluate([document], ["backend=1"], legacy_recovery_acknowledged=True)
        self.assertEqual(["backend"], acknowledged["legacy_recovery_enabled"])
        self.assertTrue(acknowledged["legacy_recovery_acknowledged"])
        default_off = self.evaluate([backend()], ["backend=1"])
        self.assertEqual([], default_off["legacy_recovery_enabled"])
        self.assertFalse(default_off["legacy_recovery_acknowledged"])

    def test_legacy_recovery_acknowledgement_is_available_on_the_cli(self):
        document = backend({"OJ_REPORT_WORKER_ENABLED": "true",
                            "OJ_REPORT_WORKER_RECOVER_LEGACY": "true"})
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "compose.json"
            path.write_text(json.dumps(document), encoding="utf-8")
            stdout = io.StringIO()
            with patch.object(sys, "argv", ["report_worker_preflight", "--compose-json", str(path),
                                              "--scale", "backend=1"]), redirect_stdout(stdout):
                self.assertEqual(2, PREFLIGHT.main())
            with patch.object(sys, "argv", ["report_worker_preflight", "--compose-json", str(path),
                                              "--scale", "backend=1",
                                              "--legacy-recovery-acknowledged"]), redirect_stdout(stdout):
                self.assertEqual(0, PREFLIGHT.main())

    def test_explicit_scale_is_required_when_compose_does_not_render_replicas(self):
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "--scale backend=N"):
            self.evaluate([backend()], [])
        result = self.evaluate([backend(deploy={"replicas": 1})], [])
        self.assertEqual(1, result["report_generation_worker_instances"])

    def test_worker_exit_and_watchdog_options_do_not_hide_instance_counts(self):
        document = backend({"OJ_REPORT_WORKER_ENABLED": "true",
                            "OJ_REPORT_NOTIFICATION_WORKER_ENABLED": "true",
                            "OJ_REPORT_WORKER_EXIT_ON_UNHEALTHY": "true",
                            "OJ_REPORT_WORKER_EXIT_GRACE_SECONDS": "5",
                            "OJ_REPORT_WORKER_EXIT_HARD_TIMEOUT_SECONDS": "20",
                            "REPORT_NOTIFICATION_POLL_INTERVAL_MS": "2000"})
        result = self.evaluate([document], ["backend=1"])
        self.assertEqual(1, result["report_generation_worker_instances"])
        self.assertEqual(1, result["report_notification_worker_instances"])

    def test_both_worker_counts_zero_one_two_with_and_without_maintenance(self):
        document = backend({"OJ_REPORT_WORKER_ENABLED": "true",
                            "OJ_REPORT_NOTIFICATION_WORKER_ENABLED": "false"})
        document["services"]["dispatcher"] = {
            "image": "musealgo/backend:latest",
            "environment": {"OJ_REPORT_WORKER_ENABLED": "false",
                            "OJ_REPORT_NOTIFICATION_WORKER_ENABLED": "true"},
        }
        for report_count in range(3):
            for notification_count in range(3):
                for maintenance in (False, True):
                    with self.subTest(report=report_count, notification=notification_count,
                                      maintenance=maintenance):
                        scales = [f"backend={report_count}", f"dispatcher={notification_count}"]
                        if report_count > 1 or notification_count > 1:
                            with self.assertRaisesRegex(PREFLIGHT.PreflightError, "more than one"):
                                self.evaluate([document], scales, maintenance)
                        elif not maintenance and (report_count == 0 or notification_count == 0):
                            with self.assertRaisesRegex(PREFLIGHT.PreflightError, "--maintenance"):
                                self.evaluate([document], scales, maintenance)
                        else:
                            result = self.evaluate([document], scales, maintenance)
                            self.assertEqual(report_count, result["report_generation_worker_instances"])
                            self.assertEqual(notification_count, result["report_notification_worker_instances"])

    def test_notification_default_and_profile_match_application_config(self):
        result = self.evaluate([backend()], ["backend=1"])
        self.assertEqual(1, result["report_notification_worker_instances"])
        result = self.evaluate([backend({"SPRING_PROFILES_ACTIVE": "worker"})],
                               ["backend=1"], maintenance=True)
        self.assertEqual(0, result["report_notification_worker_instances"])
        self.assertFalse(result["services"][0]["report_notification_worker_enabled"])
        for raw in ('{"spring":{"profiles.active":"worker"}}',
                    '{"spring.profiles":{"active":"worker"}}'):
            with self.subTest(raw=raw):
                result = self.evaluate([backend({"SPRING_APPLICATION_JSON": raw})],
                                       ["backend=1"], maintenance=True)
                self.assertEqual(0, result["report_generation_worker_instances"])
                self.assertEqual(0, result["report_notification_worker_instances"])

    def test_notification_configuration_sources_and_conflicts(self):
        sources = [
            backend({"OJ_REPORT_NOTIFICATION_WORKER_ENABLED": "false"}),
            backend({"REPORT_NOTIFICATION_WORKER_ENABLED": "false"}),
            backend({"SPRING_APPLICATION_JSON": '{"report":{"notification":{"worker-enabled":false}}}'}),
            backend({"SPRING_APPLICATION_JSON": '{"report":{"notification.worker-enabled":false}}'}),
            backend({"SPRING_APPLICATION_JSON": '{"report.notification":{"worker-enabled":false}}'}),
            backend({"SPRING_APPLICATION_JSON": '{"report.notification.worker-enabled":false}'}),
            backend({"JAVA_TOOL_OPTIONS": "-Dreport.notification.worker-enabled=false"}),
            backend(command=["java", "-jar", "app.jar", "--report.notification.worker-enabled=false"]),
            backend(command=["java", "-jar", "app.jar", "--report.notification.worker-enabled", "false"]),
        ]
        for document in sources:
            with self.subTest(document=document):
                result = self.evaluate([document], ["backend=1"], maintenance=True)
                self.assertEqual(0, result["report_notification_worker_instances"])
        conflicting = backend({"OJ_REPORT_NOTIFICATION_WORKER_ENABLED": "false",
                               "JAVA_TOOL_OPTIONS": "-Dreport.notification.worker-enabled=true"})
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "conflicting report.notification.worker-enabled"):
            self.evaluate([conflicting], ["backend=1"])
        for override in (
                {"JAVA_TOOL_OPTIONS": "-DOJ_REPORT_NOTIFICATION_WORKER_ENABLED=true"},
                {"SPRING_APPLICATION_JSON": '{"OJ_REPORT_NOTIFICATION_WORKER_ENABLED":true}'},
        ):
            conflicting = backend({"OJ_REPORT_NOTIFICATION_WORKER_ENABLED": "false", **override})
            with self.subTest(override=override), self.assertRaisesRegex(
                    PREFLIGHT.PreflightError, "conflicting report.notification.worker-enabled"):
                self.evaluate([conflicting], ["backend=1"])
        conflicting = backend({"OJ_REPORT_NOTIFICATION_WORKER_ENABLED": "false"},
                              command=["java", "-jar", "app.jar", "--OJ_REPORT_NOTIFICATION_WORKER_ENABLED=true"])
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "conflicting report.notification.worker-enabled"):
            self.evaluate([conflicting], ["backend=1"])
        unresolved = backend({"OJ_REPORT_NOTIFICATION_WORKER_ENABLED": "${MISSING}"})
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "unresolved"):
            self.evaluate([unresolved], ["backend=1"])
        opaque = backend({"SPRING_APPLICATION_JSON": '{"report":{"notification":"unknown"}}'})
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "opaque report.notification"):
            self.evaluate([opaque], ["backend=1"])
        unknown = backend({"SPRING_APPLICATION_JSON": '{"report.notification.workerEnabled":true}'})
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "uninspected worker alias"):
            self.evaluate([unknown], ["backend=1"])

    def test_spring_environment_aliases_are_counted_or_rejected(self):
        for key in ("report_notification_worker_enabled", "report.notification.worker-enabled",
                    "report_notification_worker-enabled", "report.notification.worker_enabled"):
            document = backend({"SPRING_PROFILES_ACTIVE": "worker", key: "true"})
            with self.subTest(key=key), self.assertRaisesRegex(PREFLIGHT.PreflightError, "more than one report-notification"):
                self.evaluate([document], ["backend=2"], maintenance=True)
        document = backend({"SPRING_PROFILES_ACTIVE": "worker", "report_worker_enabled": "true"})
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "more than one report-generation"):
            self.evaluate([document], ["backend=2"], maintenance=True)
        document = backend({"OJ_REPORT_NOTIFICATION_WORKER_ENABLED": "false",
                            "report_notification_worker_enabled": "true"})
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "conflicting report.notification.worker-enabled"):
            self.evaluate([document], ["backend=1"])
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "spring_config_location"):
            self.evaluate([backend({"spring_config_location": "file:/etc/oj/"})], ["backend=1"])
        profile = self.evaluate([backend({"spring_profiles_active": "worker"})], ["backend=1"], maintenance=True)
        self.assertEqual(0, profile["report_notification_worker_instances"])

    def test_system_and_command_line_aliases_cannot_hide_enabled_workers(self):
        for token, disabled in (
                ("-Dreport_worker_enabled=false", "OJ_REPORT_NOTIFICATION_WORKER_ENABLED"),
                ("-Dreport_notification_worker_enabled=false", "OJ_REPORT_WORKER_ENABLED"),
                ("--report_worker_enabled=false", "OJ_REPORT_NOTIFICATION_WORKER_ENABLED"),
                ("--report_notification_worker_enabled=false", "OJ_REPORT_WORKER_ENABLED"),
        ):
            document = backend({"SPRING_PROFILES_ACTIVE": "prod", disabled: "false"},
                               command=["java", token, "-jar", "app.jar"])
            with self.subTest(token=token), self.assertRaisesRegex(PREFLIGHT.PreflightError, "uninspected worker option"):
                self.evaluate([document], ["backend=2"], maintenance=True)
        document = backend({"SPRING_PROFILES_ACTIVE": "prod", "OJ_REPORT_WORKER_ENABLED": "false"},
                           command=["java", "-jar", "app.jar", "report_notification_worker_enabled=false"])
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "uninspected environment assignment"):
            self.evaluate([document], ["backend=2"], maintenance=True)

    def test_unrecognized_environment_and_json_aliases_cannot_hide_workers(self):
        for key, disabled in (
                ("oj_report_worker_enabled", "OJ_REPORT_NOTIFICATION_WORKER_ENABLED"),
                ("Report_Worker_Enabled", "OJ_REPORT_NOTIFICATION_WORKER_ENABLED"),
                ("REPORT_NOTIFICATION_WORKERENABLED", "OJ_REPORT_WORKER_ENABLED"),
                ("report.notification.workerEnabled", "OJ_REPORT_WORKER_ENABLED"),
                ("report_notification.worker-enabled", "OJ_REPORT_WORKER_ENABLED"),
        ):
            document = backend({"SPRING_PROFILES_ACTIVE": "prod", disabled: "false", key: "false"})
            with self.subTest(key=key), self.assertRaisesRegex(PREFLIGHT.PreflightError, "uninspected.*alias"):
                self.evaluate([document], ["backend=2"], maintenance=True)
        document = backend({"SPRING_PROFILES_ACTIVE": "prod", "OJ_REPORT_WORKER_ENABLED": "false",
                            "SPRING_APPLICATION_JSON": '{"report_notification_worker_enabled":false}'})
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "uninspected.*alias"):
            self.evaluate([document], ["backend=2"], maintenance=True)
        document = backend({"Spring_Profiles_Active": "worker"})
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "uninspected.*alias"):
            self.evaluate([document], ["backend=2"], maintenance=True)

    def test_secret_mounts_cannot_hide_worker_configuration(self):
        for target in ("/app/config/application-worker.yml", "/app/config/report", "/app"):
            document = backend({"SPRING_PROFILES_ACTIVE": "worker"})
            document["services"]["backend"]["secrets"] = [{"source": "synthetic", "target": target}]
            with self.subTest(target=target), self.assertRaisesRegex(PREFLIGHT.PreflightError, "mounted Spring config"):
                self.evaluate([document], ["backend=2"], maintenance=True)
        for secret in ("ai_master_key", {"source": "ai_master_key", "target": "/run/secrets/ai_master_key"},
                       {"source": "tls", "target": "/run/secrets/tls/application.yml"}):
            document = backend()
            document["services"]["backend"]["secrets"] = [secret]
            with self.subTest(secret=secret):
                result = self.evaluate([document], ["backend=1"])
                self.assertEqual(1, result["report_notification_worker_instances"])
        document = backend()
        document["services"]["backend"]["working_dir"] = "/run/secrets/tls"
        document["services"]["backend"]["secrets"] = [{"source": "tls", "target": "/run/secrets/tls/application.yml"}]
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, "mounted Spring config"):
            self.evaluate([document], ["backend=1"])
if __name__ == "__main__":
    unittest.main()
