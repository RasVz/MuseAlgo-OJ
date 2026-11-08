#!/usr/bin/env python3
"""Fail-closed preflight for report and notification worker counts in Compose config."""
from __future__ import annotations

import argparse
import json
import posixpath
import re
import shlex
import sys
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any


KNOWN_PROFILES = {"worker", "prod"}
REPORT_WORKER_ENV_KEYS = {"OJ_REPORT_WORKER_ENABLED", "REPORT_WORKER_ENABLED"}
NOTIFICATION_WORKER_ENV_KEYS = {"OJ_REPORT_NOTIFICATION_WORKER_ENABLED",
                                "REPORT_NOTIFICATION_WORKER_ENABLED",
                                "REPORT_NOTIFICATION_WORKERENABLED"}
KNOWN_NOTIFICATION_PROPERTIES = {
    "worker-enabled", "poll-interval-ms", "claim-batch-size", "dispatch-lease-ttl-seconds",
}
KNOWN_NOTIFICATION_ENV_FLAGS = NOTIFICATION_WORKER_ENV_KEYS | {
    "REPORT_NOTIFICATION_POLL_INTERVAL_MS", "REPORT_NOTIFICATION_CLAIM_BATCH_SIZE",
    "REPORT_NOTIFICATION_DISPATCH_LEASE_TTL_SECONDS",
    "REPORT_NOTIFICATION_POLLINTERVALMS", "REPORT_NOTIFICATION_CLAIMBATCHSIZE",
    "REPORT_NOTIFICATION_DISPATCHLEASETTLSECONDS",
}
KNOWN_WORKER_PROPERTIES = {
    "enabled", "threads", "poll-interval-ms", "lease-ttl-seconds",
    "heartbeat-interval-ms", "max-runtime-seconds", "cancel-grace-seconds",
    "watchdog-interval-ms", "recover-legacy-generating", "exit-on-unhealthy",
    "exit-grace-seconds", "exit-hard-timeout-seconds",
}
KNOWN_WORKER_ENV_FLAGS = {
    "OJ_REPORT_WORKER_ENABLED", "OJ_REPORT_WORKER_THREADS", "OJ_REPORT_WORKER_POLL_INTERVAL_MS",
    "OJ_REPORT_WORKER_LEASE_TTL_SECONDS", "OJ_REPORT_WORKER_HEARTBEAT_INTERVAL_MS",
    "OJ_REPORT_WORKER_MAX_RUNTIME_SECONDS", "OJ_REPORT_WORKER_CANCEL_GRACE_SECONDS",
    "OJ_REPORT_WORKER_WATCHDOG_INTERVAL_MS", "OJ_REPORT_WORKER_RECOVER_LEGACY",
    "OJ_REPORT_WORKER_EXIT_ON_UNHEALTHY", "OJ_REPORT_WORKER_EXIT_GRACE_SECONDS",
    "OJ_REPORT_WORKER_EXIT_HARD_TIMEOUT_SECONDS",
    "REPORT_WORKER_ENABLED", "REPORT_WORKER_THREADS", "REPORT_WORKER_POLL_INTERVAL_MS",
    "REPORT_WORKER_LEASE_TTL_SECONDS", "REPORT_WORKER_HEARTBEAT_INTERVAL_MS",
    "REPORT_WORKER_MAX_RUNTIME_SECONDS", "REPORT_WORKER_CANCEL_GRACE_SECONDS",
    "REPORT_WORKER_WATCHDOG_INTERVAL_MS", "REPORT_WORKER_RECOVER_LEGACY_GENERATING",
    "REPORT_WORKER_EXIT_ON_UNHEALTHY", "REPORT_WORKER_EXIT_GRACE_SECONDS",
    "REPORT_WORKER_EXIT_HARD_TIMEOUT_SECONDS",
}
CONFIG_SOURCE_ENV_KEYS = {
    "SPRING_CONFIG_LOCATION", "SPRING_CONFIG_ADDITIONAL_LOCATION", "SPRING_CONFIG_NAME",
    "SPRING_CONFIG_IMPORT",
}
UNRESOLVED = re.compile(r"\$\{|\$[A-Za-z_]|<[^>]+>")


class PreflightError(ValueError):
    pass


@dataclass(frozen=True)
class ServicePlan:
    service: str
    replicas: int
    worker_enabled: bool
    notification_worker_enabled: bool


def _as_tokens(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [str(item) for item in value]
    if isinstance(value, str):
        return shlex.split(value)
    raise PreflightError("Compose command/entrypoint must be a string or string array")


def _environment(service: dict[str, Any]) -> dict[str, Any]:
    raw = service.get("environment") or {}
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, list):
        result: dict[str, Any] = {}
        for entry in raw:
            if not isinstance(entry, str) or "=" not in entry:
                raise PreflightError("unresolved Compose environment entry")
            key, value = entry.split("=", 1)
            result[key] = value
        return result
    raise PreflightError("Compose environment must be a mapping or KEY=VALUE array")


def _environment_key(key: str) -> str:
    # 仅用于发现可能的别名；可识别名称仍需按来源逐一验证。
    return key.replace(".", "_").replace("-", "_").upper()


def _spring_environment_aliases(name: str) -> set[str]:
    # 与 Spring 的 SystemEnvironmentPropertySource 一致：原样、替换点号/连字符及其大写形式。
    variants = {name, name.replace(".", "_"), name.replace("-", "_"),
                name.replace(".", "_").replace("-", "_")}
    return variants | {item.upper() for item in variants}


def _bool(value: Any, source: str) -> bool:
    if isinstance(value, bool):
        return value
    if not isinstance(value, (str, int)) or isinstance(value, int) and value not in (0, 1):
        raise PreflightError(f"{source} is not a boolean")
    normalized = str(value).strip().lower()
    if UNRESOLVED.search(normalized):
        raise PreflightError(f"{source} is unresolved")
    if normalized in {"true", "1", "yes", "on"}:
        return True
    if normalized in {"false", "0", "no", "off"}:
        return False
    raise PreflightError(f"{source} must be true or false")


def _json_overrides(raw: Any, source: str) -> tuple[list[tuple[str, Any]], list[tuple[str, Any]], list[tuple[str, Any]]]:
    if raw is None or raw == "":
        return [], [], []
    if not isinstance(raw, str):
        raise PreflightError(f"{source} must contain JSON text")
    try:
        parsed = json.loads(raw)
    except (TypeError, json.JSONDecodeError) as error:
        raise PreflightError(f"{source} is malformed") from error
    if not isinstance(parsed, dict):
        raise PreflightError(f"{source} must be a JSON object")
    report = parsed.get("report")
    if report is not None and not isinstance(report, dict):
        raise PreflightError(f"{source} has an opaque report configuration")
    worker_values = []
    notification_values = []
    profile_values = []
    # Spring 会展平 JSON 的嵌套键和带点号的键；检查所有等价路径，防止漏掉覆盖值。
    def inspect_worker_config(value: Any, path: str = "") -> None:
        if (_environment_key(path) in REPORT_WORKER_ENV_KEYS | NOTIFICATION_WORKER_ENV_KEYS
                and path not in {"report.worker.enabled", "report.notification.worker-enabled",
                                 "OJ_REPORT_WORKER_ENABLED", "OJ_REPORT_NOTIFICATION_WORKER_ENABLED"}):
            raise PreflightError(f"{source} has an uninspected worker alias: {path}")
        if path in {"spring.config.location", "spring.config.additional-location",
                    "spring.config.name", "spring.config.import"} and value:
            raise PreflightError(f"{source} selects an uninspected Spring config location")
        if path == "spring.profiles.active":
            profile_values.append((f"{source}:{path}", value))
            return
        for prefix, known, enabled_key, values in (
                ("report.worker", KNOWN_WORKER_PROPERTIES, "enabled", worker_values),
                ("report.notification", KNOWN_NOTIFICATION_PROPERTIES, "worker-enabled", notification_values)):
            if path == prefix and not isinstance(value, dict):
                raise PreflightError(f"{source} has an opaque {prefix} configuration")
            if path.startswith(prefix + "."):
                key = path[len(prefix) + 1:]
                if key not in known or isinstance(value, dict):
                    raise PreflightError(f"{source} has unknown {prefix} properties")
                if key == enabled_key:
                    values.append((f"{source}:{path}", value))
                return
        if isinstance(value, dict):
            for key, item in value.items():
                inspect_worker_config(item, f"{path}.{key}" if path else key)

    inspect_worker_config(parsed)
    for key in {"OJ_REPORT_NOTIFICATION_WORKER_ENABLED"}:
        if key in parsed:
            notification_values.append((f"{source}:{key}", parsed[key]))
    for key in {"OJ_REPORT_WORKER_ENABLED"}:
        if key in parsed:
            worker_values.append((f"{source}:{key}", parsed[key]))
    return worker_values, notification_values, profile_values


def _split_profiles(value: Any, source: str) -> set[str]:
    if value is None:
        raise PreflightError(f"{source} is unresolved")
    if isinstance(value, list):
        parts = [str(item).strip() for item in value]
    elif isinstance(value, str):
        if UNRESOLVED.search(value):
            raise PreflightError(f"{source} is unresolved")
        parts = [part.strip() for part in re.split(r"[,;]", value)]
    else:
        raise PreflightError(f"{source} must be a profile name or list")
    profiles = {part for part in parts if part}
    unknown = profiles - KNOWN_PROFILES
    if unknown:
        raise PreflightError(f"{source} contains uninspected profiles: {', '.join(sorted(unknown))}")
    return profiles


def _command_properties(tokens: list[str], source: str) -> tuple[list[tuple[str, Any]], list[tuple[str, Any]], list[tuple[str, Any]]]:
    worker_values: list[tuple[str, Any]] = []
    notification_values: list[tuple[str, Any]] = []
    profile_values: list[tuple[str, Any]] = []
    i = 0
    while i < len(tokens):
        token = tokens[i]
        option = token[2:] if token.startswith(("-D", "--")) else ""
        option_key = option.split("=", 1)[0]
        if option_key in {"OJ_REPORT_WORKER_ENABLED", "OJ_REPORT_NOTIFICATION_WORKER_ENABLED"}:
            value = option.split("=", 1)[1] if "=" in option else None
            if value is None:
                if token.startswith("-D"):
                    raise PreflightError(f"missing value for {option_key} in {source}")
                i += 1
                if i >= len(tokens):
                    raise PreflightError(f"missing value for {option_key} in {source}")
                value = tokens[i]
            target = worker_values if option_key in REPORT_WORKER_ENV_KEYS else notification_values
            target.append((source + ":" + option_key, value))
        elif (_environment_key(option_key) in REPORT_WORKER_ENV_KEYS | NOTIFICATION_WORKER_ENV_KEYS
              and option_key not in {"report.worker.enabled", "report.notification.worker-enabled"}):
            # JVM 属性和命令行不使用系统环境的下划线别名规则。
            raise PreflightError(f"uninspected worker option {option_key} in {source}")
        elif token.startswith("--report.worker."):
            key = token[len("--report.worker."):].split("=", 1)[0]
            if key not in KNOWN_WORKER_PROPERTIES:
                raise PreflightError(f"unknown report worker flag in {source}")
            value = token.split("=", 1)[1] if "=" in token else None
            if value is None:
                i += 1
                if i >= len(tokens):
                    raise PreflightError(f"missing value for report.worker.{key} in {source}")
                value = tokens[i]
            if UNRESOLVED.search(value):
                raise PreflightError(f"unresolved report.worker.{key} flag in {source}")
            if key == "enabled":
                worker_values.append((source, value))
        elif token.startswith("--report.notification."):
            key = token[len("--report.notification."):].split("=", 1)[0]
            if key not in KNOWN_NOTIFICATION_PROPERTIES:
                raise PreflightError(f"unknown report notification flag in {source}")
            value = token.split("=", 1)[1] if "=" in token else None
            if value is None:
                i += 1
                if i >= len(tokens):
                    raise PreflightError(f"missing value for report.notification.{key} in {source}")
                value = tokens[i]
            if UNRESOLVED.search(value):
                raise PreflightError(f"unresolved report.notification.{key} flag in {source}")
            if key == "worker-enabled":
                notification_values.append((source, value))
        elif token.startswith("--spring.profiles.active="):
            profile_values.append((source, token.split("=", 1)[1]))
        elif token == "--spring.profiles.active":
            i += 1
            if i >= len(tokens):
                raise PreflightError(f"missing value for spring.profiles.active in {source}")
            profile_values.append((source, tokens[i]))
        elif token.startswith("--spring.config."):
            raise PreflightError(f"uninspected Spring config flag in {source}")
        elif token.startswith("-Dreport.worker."):
            match = re.fullmatch(r"-Dreport\.worker\.([a-z-]+)=(.+)", token)
            if not match or match.group(1) not in KNOWN_WORKER_PROPERTIES:
                raise PreflightError(f"unknown report worker system property in {source}")
            if UNRESOLVED.search(match.group(2)):
                raise PreflightError(f"unresolved report worker system property in {source}")
            if match.group(1) == "enabled":
                worker_values.append((source, match.group(2)))
        elif token.startswith("-Dreport.notification."):
            match = re.fullmatch(r"-Dreport\.notification\.([a-z-]+)=(.+)", token)
            if not match or match.group(1) not in KNOWN_NOTIFICATION_PROPERTIES:
                raise PreflightError(f"unknown report notification system property in {source}")
            if UNRESOLVED.search(match.group(2)):
                raise PreflightError(f"unresolved report notification system property in {source}")
            if match.group(1) == "worker-enabled":
                notification_values.append((source, match.group(2)))
        elif token.startswith("-Dspring.profiles.active="):
            profile_values.append((source, token.split("=", 1)[1]))
        elif token.startswith("-Dspring.config."):
            raise PreflightError(f"uninspected Spring config system property in {source}")
        elif token.startswith("-Dspring.application.json="):
            json_workers, json_notifications, json_profiles = _json_overrides(token.split("=", 1)[1], source + ":spring.application.json")
            worker_values.extend(json_workers)
            notification_values.extend(json_notifications)
            profile_values.extend(json_profiles)
        elif token == "--spring.application.json":
            i += 1
            if i >= len(tokens):
                raise PreflightError(f"missing value for spring.application.json in {source}")
            json_workers, json_notifications, json_profiles = _json_overrides(tokens[i], source + ":spring.application.json")
            worker_values.extend(json_workers)
            notification_values.extend(json_notifications)
            profile_values.extend(json_profiles)
        elif token.startswith("--spring.application.json="):
            json_workers, json_notifications, json_profiles = _json_overrides(token.split("=", 1)[1], source + ":spring.application.json")
            worker_values.extend(json_workers)
            notification_values.extend(json_notifications)
            profile_values.extend(json_profiles)
        elif "=" in token and not token.startswith(("-", "/")):
            raw_key, _ = token.split("=", 1)
            key = _environment_key(raw_key)
            if (key.startswith(("OJ_REPORT_WORKER_", "REPORT_WORKER_", "OJ_REPORT_NOTIFICATION_",
                                "REPORT_NOTIFICATION_")) or key in CONFIG_SOURCE_ENV_KEYS
                    or key in {"SPRING_PROFILES_ACTIVE", "SPRING_APPLICATION_JSON"}):
                # 无法证明裸参数是 shell 环境赋值还是被 JVM 忽略的应用参数。
                raise PreflightError(f"uninspected environment assignment {raw_key} in {source}")
        i += 1
    return worker_values, notification_values, profile_values


def _launcher_tokens(service: dict[str, Any], service_name: str) -> list[str]:
    tokens = _as_tokens(service.get("entrypoint")) + _as_tokens(service.get("command"))
    shells = {"sh", "bash", "dash", "ash", "zsh"}
    for index, token in enumerate(tokens):
        if Path(token).name not in shells:
            if token.endswith(".sh") and index == 0:
                raise PreflightError(f"opaque shell launcher in service {service_name}")
            continue
        if index + 2 >= len(tokens) or tokens[index + 1] != "-c":
            raise PreflightError(f"opaque shell launcher in service {service_name}")
        script = tokens[index + 2]
        # Parse only a simple, literal command. Shell expansion and control flow can hide
        # Spring arguments, so these deployments must be rendered without a shell wrapper.
        if UNRESOLVED.search(script) or re.search(r"[;&|<>`()]", script):
            raise PreflightError(f"opaque shell command in service {service_name}")
        try:
            nested = shlex.split(script)
        except ValueError as error:
            raise PreflightError(f"malformed shell command in service {service_name}") from error
        if any(Path(item).name in shells for item in nested):
            raise PreflightError(f"nested shell launcher in service {service_name}")
        return tokens[:index] + nested + tokens[index + 3:]
    return tokens


def _check_mount_target(target: Any, working_dir: str, service_name: str, *, secret: bool = False) -> None:
    if not isinstance(target, str) or not target.startswith("/") or UNRESOLVED.search(target):
        raise PreflightError(f"uninspected volume target in service {service_name}")
    target = posixpath.normpath(target)
    # Spring 会扫描工作目录及 config/*/，不能只检查 /config 目录自身。
    secret_outside_search = (secret and target.startswith("/run/secrets/")
                             and working_dir != "/run/secrets" and not working_dir.startswith("/run/secrets/"))
    if ((not secret_outside_search and any(target.endswith(suffix) for suffix in (".yml", ".yaml", ".properties")))
            or "config" in PurePosixPath(target).parts
            or target == "/" or target == working_dir
            or working_dir.startswith(target.rstrip("/") + "/")):
        raise PreflightError(f"mounted Spring config in service {service_name} is not inspected")


def _effective_workers(service: dict[str, Any], service_name: str) -> tuple[bool, bool]:
    environment = _environment(service)
    if service.get("env_file"):
        raise PreflightError(f"opaque env_file in service {service_name}; provide fully rendered Compose config")
    if service.get("configs"):
        raise PreflightError(f"mounted Compose configs in service {service_name} are not inspected")
    if service.get("volumes_from"):
        raise PreflightError(f"inherited volumes in service {service_name} are not inspected")
    working_dir = service.get("working_dir") or "/app"
    if not isinstance(working_dir, str) or not working_dir.startswith("/") or UNRESOLVED.search(working_dir):
        raise PreflightError(f"uninspected working directory in service {service_name}")
    working_dir = posixpath.normpath(working_dir)
    for volume in service.get("volumes") or []:
        if isinstance(volume, dict):
            target = volume.get("target")
        elif isinstance(volume, str):
            parts = volume.split(":")
            target = parts[1] if len(parts) > 1 else parts[0]
        else:
            target = None
        _check_mount_target(target, working_dir, service_name)
    secrets = service.get("secrets") or []
    if not isinstance(secrets, list):
        raise PreflightError(f"uninspected Compose secrets in service {service_name}")
    for secret in secrets:
        if isinstance(secret, dict):
            target = secret.get("target") or secret.get("source")
        else:
            target = secret
        if isinstance(target, str) and not target.startswith("/"):
            target = posixpath.join("/run/secrets", target)
        _check_mount_target(target, working_dir, service_name, secret=True)

    worker_values: list[tuple[str, Any]] = []
    notification_values: list[tuple[str, Any]] = []
    profile_values: list[tuple[str, Any]] = []
    for key, value in environment.items():
        if not isinstance(key, str):
            raise PreflightError(f"invalid environment key in service {service_name}")
        normalized = _environment_key(key)
        source = f"{service_name}:environment:{key}"
        if normalized.startswith(("OJ_REPORT_WORKER_", "REPORT_WORKER_")) \
                and normalized not in KNOWN_WORKER_ENV_FLAGS:
            raise PreflightError(f"unknown report worker environment flag in service {service_name}")
        if normalized.startswith(("OJ_REPORT_NOTIFICATION_", "REPORT_NOTIFICATION_")) \
                and normalized not in KNOWN_NOTIFICATION_ENV_FLAGS:
            raise PreflightError(f"unknown report notification environment flag in service {service_name}")
        if normalized in CONFIG_SOURCE_ENV_KEYS and value:
            raise PreflightError(f"uninspected {key} in service {service_name}")
        if normalized in REPORT_WORKER_ENV_KEYS:
            if key not in _spring_environment_aliases("report.worker.enabled") | {"OJ_REPORT_WORKER_ENABLED"}:
                raise PreflightError(f"uninspected report worker environment alias {key} in service {service_name}")
            worker_values.append((source, value))
        elif normalized in NOTIFICATION_WORKER_ENV_KEYS:
            if key not in (_spring_environment_aliases("report.notification.worker-enabled")
                           | {"OJ_REPORT_NOTIFICATION_WORKER_ENABLED"}):
                raise PreflightError(f"uninspected notification worker environment alias {key} in service {service_name}")
            notification_values.append((source, value))
        elif normalized == "SPRING_PROFILES_ACTIVE":
            if key not in _spring_environment_aliases("spring.profiles.active"):
                raise PreflightError(f"uninspected Spring profile environment alias {key} in service {service_name}")
            profile_values.append((source, value))
        elif normalized == "SPRING_APPLICATION_JSON":
            if key not in _spring_environment_aliases("spring.application.json"):
                raise PreflightError(f"uninspected Spring JSON environment alias {key} in service {service_name}")
            json_workers, json_notifications, json_profiles = _json_overrides(value, source)
            worker_values.extend(json_workers)
            notification_values.extend(json_notifications)
            profile_values.extend(json_profiles)

    for env_key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS"):
        raw = environment.get(env_key)
        if raw:
            command_workers, command_notifications, command_profiles = _command_properties(
                shlex.split(str(raw)), f"{service_name}:{env_key}")
            worker_values.extend(command_workers)
            notification_values.extend(command_notifications)
            profile_values.extend(command_profiles)

    command_tokens = _launcher_tokens(service, service_name)
    command_workers, command_notifications, command_profiles = _command_properties(command_tokens, f"{service_name}:command")
    worker_values.extend(command_workers)
    notification_values.extend(command_notifications)
    profile_values.extend(command_profiles)

    parsed_profiles = [_split_profiles(value, source) for source, value in profile_values]
    if parsed_profiles and any(value != parsed_profiles[0] for value in parsed_profiles[1:]):
        raise PreflightError(f"conflicting Spring profile overrides in service {service_name}")
    active_profiles = parsed_profiles[0] if parsed_profiles else set()

    def effective(values: list[tuple[str, Any]], property_name: str) -> bool:
        explicit_values = [_bool(value, source) for source, value in values]
        if explicit_values and any(value != explicit_values[0] for value in explicit_values[1:]):
            raise PreflightError(f"conflicting {property_name} overrides in service {service_name}")
        # worker 配置默认关闭两个 worker，常规配置默认开启。
        return explicit_values[0] if explicit_values else "worker" not in active_profiles

    return (effective(worker_values, "report.worker.enabled"),
            effective(notification_values, "report.notification.worker-enabled"))


def _is_migration_service(name: str, service: dict[str, Any]) -> bool:
    text = " ".join(_as_tokens(service.get("entrypoint")) + _as_tokens(service.get("command")))
    return name.lower() == "migrate" or "FlywayMigrationRunner" in text


def _application_services(documents: list[dict[str, Any]]) -> list[tuple[str, dict[str, Any]]]:
    backend_images: set[str] = set()
    for document in documents:
        services = document.get("services")
        if not isinstance(services, dict):
            raise PreflightError("Compose JSON must contain a services mapping")
        for name, service in services.items():
            if isinstance(service, dict) and (name == "backend" or "app.jar" in " ".join(
                    _as_tokens(service.get("entrypoint")) + _as_tokens(service.get("command")))):
                if service.get("image"):
                    backend_images.add(str(service["image"]))

    result: list[tuple[str, dict[str, Any]]] = []
    for document in documents:
        for name, service in document["services"].items():
            if not isinstance(service, dict) or _is_migration_service(name, service):
                continue
            command = " ".join(_as_tokens(service.get("entrypoint")) + _as_tokens(service.get("command")))
            is_backend = name == "backend" or "app.jar" in command or (
                service.get("image") is not None and str(service["image"]) in backend_images)
            if is_backend:
                result.append((str(name), service))
    if not result:
        raise PreflightError("no MuseAlgo backend runtime services found in the supplied Compose JSON")
    return result


def _scale_overrides(values: list[str]) -> dict[str, int]:
    result: dict[str, int] = {}
    for value in values:
        if "=" not in value:
            raise PreflightError("--scale must use SERVICE=COUNT")
        name, raw_count = value.split("=", 1)
        if not name or not raw_count.isdigit():
            raise PreflightError("--scale must use SERVICE=nonnegative-integer")
        if name in result:
            raise PreflightError(f"duplicate --scale for service {name}")
        result[name] = int(raw_count)
    return result


def _legacy_recovery_enabled(service: dict[str, Any], service_name: str) -> bool:
    """Pre-lease recovery mutates legacy rows, so it needs an explicit operator acknowledgement."""
    environment = _environment(service)
    for key in ("OJ_REPORT_WORKER_RECOVER_LEGACY", "REPORT_WORKER_RECOVER_LEGACY_GENERATING"):
        if key in environment and environment[key] is not None:
            return _bool(environment[key], f"{service_name}:environment:{key}")
    return False


def evaluate(compose_paths: list[Path], scales: dict[str, int], maintenance: bool,
             legacy_recovery_acknowledged: bool = False) -> dict[str, Any]:
    documents: list[dict[str, Any]] = []
    for path in compose_paths:
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise PreflightError(f"cannot read rendered Compose JSON: {path.name}") from error
        if not isinstance(value, dict):
            raise PreflightError(f"rendered Compose JSON is not an object: {path.name}")
        documents.append(value)

    plans: list[ServicePlan] = []
    consumed_scale_names: set[str] = set()
    for name, service in _application_services(documents):
        deploy = service.get("deploy") or {}
        declared_scale = service.get("scale")
        replicas = deploy.get("replicas") if isinstance(deploy, dict) else None
        if replicas is not None and (not isinstance(replicas, int) or isinstance(replicas, bool) or replicas < 0):
            raise PreflightError(f"invalid deploy.replicas for service {name}")
        if declared_scale is not None and (not isinstance(declared_scale, int) or isinstance(declared_scale, bool) or declared_scale < 0):
            raise PreflightError(f"invalid scale for service {name}")
        rendered_scale = replicas if replicas is not None else declared_scale
        explicit_scale = scales.get(name)
        if rendered_scale is None and explicit_scale is None:
            raise PreflightError(
                f"service {name} has no rendered replica count; pass --scale {name}=N "
                "to match the intended Compose up --scale value")
        if rendered_scale is not None and explicit_scale is not None and rendered_scale != explicit_scale:
            raise PreflightError(f"--scale conflicts with rendered replica count for service {name}")
        count = explicit_scale if explicit_scale is not None else rendered_scale
        consumed_scale_names.add(name)
        enabled, notification_enabled = _effective_workers(service, name)
        plans.append(ServicePlan(name, count, enabled, notification_enabled))
    unknown_scales = set(scales) - consumed_scale_names
    if unknown_scales:
        raise PreflightError(f"--scale names unknown backend services: {', '.join(sorted(unknown_scales))}")

    enabled_instances = sum(plan.replicas for plan in plans if plan.worker_enabled)
    notification_instances = sum(plan.replicas for plan in plans if plan.notification_worker_enabled)
    if enabled_instances > 1:
        raise PreflightError("more than one report-generation worker instance is enabled")
    if notification_instances > 1:
        raise PreflightError("more than one report-notification dispatcher instance is enabled")
    if enabled_instances == 0 and not maintenance:
        raise PreflightError("zero report-generation workers requires explicit --maintenance")
    if notification_instances == 0 and not maintenance:
        raise PreflightError("zero report-notification dispatchers requires explicit --maintenance")

    legacy_recovery_services = sorted(
        name for name, service in _application_services(documents)
        if _legacy_recovery_enabled(service, name)
    )
    if legacy_recovery_services and not legacy_recovery_acknowledged:
        raise PreflightError(
            "legacy report recovery is enabled for " + ", ".join(legacy_recovery_services)
            + "; confirm the old synchronous entry point and the old worker are stopped, then pass "
              "--legacy-recovery-acknowledged")
    return {
        "mode": "maintenance" if maintenance else "normal",
        "report_generation_worker_instances": enabled_instances,
        "report_notification_worker_instances": notification_instances,
        "legacy_recovery_enabled": legacy_recovery_services,
        "legacy_recovery_acknowledged": legacy_recovery_acknowledged,
        "services": [
            {"service": plan.service, "instances": plan.replicas,
             "report_worker_enabled": plan.worker_enabled,
             "report_notification_worker_enabled": plan.notification_worker_enabled}
            for plan in plans
        ],
        "scope": "only the supplied rendered Compose JSON and explicit --scale values",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compose-json", action="append", required=True,
                        help="rendered docker compose config --format json; may be repeated for each checked project")
    parser.add_argument("--scale", action="append", default=[], metavar="SERVICE=N",
                        help="explicit intended Compose up --scale value when replicas are not rendered")
    parser.add_argument("--maintenance", action="store_true",
                        help="explicitly permit zero report or notification worker instances")
    parser.add_argument("--legacy-recovery-acknowledged", action="store_true",
                        help="confirm the old synchronous entry point and old worker are stopped; "
                             "required when any service enables legacy recovery")
    args = parser.parse_args()
    try:
        result = evaluate([Path(value) for value in args.compose_json],
                          _scale_overrides(args.scale), args.maintenance,
                          args.legacy_recovery_acknowledged)
    except PreflightError as error:
        print(f"report-worker preflight failed: {error}", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
