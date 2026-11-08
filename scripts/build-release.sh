#!/usr/bin/env bash
# ============================================================
# MuseAlgo-OJ 发行构建入口
# ------------------------------------------------------------
# 用途：从干净的候选提交串行构建后端/前端生产镜像，注入 OCI 追溯标签，
#       并生成可审计的发行清单（提交 SHA、镜像 ID、迁移锁摘要、工具链版本）。
#
# 只在本地构建与保存镜像：不 push、不建 PR、不改远端 CI。
#
# 用法（仓库根目录或任意目录执行）：
#   scripts/build-release.sh                        # 构建并写默认发行清单
#   scripts/build-release.sh --allow-dirty          # 允许脏树（清单记录 dirty=true）
#   scripts/build-release.sh --version 2026.10.21   # 指定版本（默认 git describe）
#   scripts/build-release.sh --manifest /secure/manifest.json
#   scripts/build-release.sh --dry-run              # 只打印计划，不构建
#   scripts/build-release.sh --no-cache
#   scripts/build-release.sh --build-arg MAVEN_MIRROR=https://maven.aliyun.com/repository/public
#
# 默认镜像：musealgo-oj/backend:<version>、musealgo-oj/frontend:<version>
# 默认清单：<仓库父目录>/outputs/releases/<UTC时间>-<短SHA>/release-manifest.json
#           （可用 --manifest 或 MUSEALGO_RELEASE_OUTPUT_DIR 改到仓库外任意位置）
#
# 资源需求：完整构建需要可用 BuildKit 与 >=4GB 内存余量；串行执行，
#          不要与其它重型构建并发（本机 8GB 场景下这是硬要求）。
# 退出码：0 成功；1 参数/基线/迁移校验失败；其它为某个镜像构建的原始退出码。
# ============================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

ALLOW_DIRTY=0
DRY_RUN=0
NO_CACHE=0
VERSION=""
MANIFEST=""
BACKEND_IMAGE="musealgo-oj/backend"
FRONTEND_IMAGE="musealgo-oj/frontend"
BACKEND_TAG=""
FRONTEND_TAG=""
EXTRA_BUILD_ARGS=()

usage() {
  sed -n '2,23p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

fail() { echo "错误：$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --allow-dirty) ALLOW_DIRTY=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --no-cache) NO_CACHE=1; shift ;;
    --version) VERSION="${2:?--version 需要参数}"; shift 2 ;;
    --manifest) MANIFEST="${2:?--manifest 需要参数}"; shift 2 ;;
    --backend-image) BACKEND_IMAGE="${2:?--backend-image 需要参数}"; shift 2 ;;
    --frontend-image) FRONTEND_IMAGE="${2:?--frontend-image 需要参数}"; shift 2 ;;
    --backend-tag) BACKEND_TAG="${2:?--backend-tag 需要参数}"; shift 2 ;;
    --frontend-tag) FRONTEND_TAG="${2:?--frontend-tag 需要参数}"; shift 2 ;;
    --build-arg) EXTRA_BUILD_ARGS+=(--build-arg "${2:?--build-arg 需要 KEY=VALUE}"); shift 2 ;;
    -h|--help) usage 0 ;;
    *) fail "未知参数：$1（--help 查看用法）" ;;
  esac
done

command -v docker >/dev/null 2>&1 || fail "未找到 docker"
command -v git >/dev/null 2>&1 || fail "未找到 git"
command -v python3 >/dev/null 2>&1 || fail "未找到 python3"

# ---------- 1. 记录并校验构建基线 ----------
GIT_SHA="$(git rev-parse HEAD)"
GIT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
GIT_DIRTY=0
DIRTY_FILES=()
while IFS= read -r line; do
  [[ -n "$line" ]] && DIRTY_FILES+=("$line")
done < <(git status --porcelain)
if [[ ${#DIRTY_FILES[@]} -gt 0 ]]; then
  GIT_DIRTY=1
  if [[ "$ALLOW_DIRTY" != "1" ]]; then
    echo "工作树不干净，拒绝生成发行镜像（发行产物必须可追溯到唯一提交）：" >&2
    printf '  %s\n' "${DIRTY_FILES[@]}" >&2
    echo "确认要以脏树构建时显式加 --allow-dirty（清单会如实记录 dirty=true）。" >&2
    exit 1
  fi
fi

if [[ -z "$VERSION" ]]; then
  if VERSION_RAW="$(git describe --tags --always --dirty 2>/dev/null)"; then
    VERSION="$VERSION_RAW"
  else
    VERSION="0.0.0-${GIT_SHA:0:12}"
  fi
fi
BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

BACKEND_TAG="${BACKEND_TAG:-$BACKEND_IMAGE:$VERSION}"
FRONTEND_TAG="${FRONTEND_TAG:-$FRONTEND_IMAGE:$VERSION}"

if [[ -z "$MANIFEST" ]]; then
  OUTPUT_ROOT="${MUSEALGO_RELEASE_OUTPUT_DIR:-$REPO_ROOT/../outputs/releases}"
  MANIFEST="$OUTPUT_ROOT/${BUILD_TIME//[:]/}-${GIT_SHA:0:12}/release-manifest.json"
fi
MANIFEST_DIR="$(dirname "$MANIFEST")"
# --dry-run 只打印计划，不创建任何目录（避免留下空目录/误以为已产出清单）。
if [[ "$DRY_RUN" != "1" ]]; then
  mkdir -p "$MANIFEST_DIR"
  MANIFEST_DIR="$(cd "$MANIFEST_DIR" && pwd)"
  MANIFEST="$MANIFEST_DIR/$(basename "$MANIFEST")"
fi
BUILD_LOG_DIR="$MANIFEST_DIR/build-logs"

# 发行清单不得落在仓库工作树内（避免把证据/镜像元数据提交进公开仓库）。
case "$MANIFEST" in
  "${REPO_ROOT}"/*) echo "警告：发行清单位于仓库工作树内（${MANIFEST}），请勿提交。" >&2 ;;
esac

echo "==> 发行构建"
echo "    仓库:     $REPO_ROOT"
echo "    提交:     ${GIT_SHA}（分支 ${GIT_BRANCH}，dirty=${GIT_DIRTY}）"
echo "    版本:     $VERSION"
echo "    构建时间: $BUILD_TIME"
echo "    后端镜像: $BACKEND_TAG"
echo "    前端镜像: $FRONTEND_TAG"
echo "    发行清单: $MANIFEST"
echo "    构建日志: $BUILD_LOG_DIR"
echo

if [[ "$DRY_RUN" == "1" ]]; then
  echo "==> --dry-run：未执行构建。"
  exit 0
fi

mkdir -p "$BUILD_LOG_DIR"

# ---------- 2. 串行构建（后端 → 前端），逐个记录命令、退出码与耗时 ----------
BUILD_NAMES=()
BUILD_CMDS=()
BUILD_EXITS=()
BUILD_SECONDS=()
BUILD_LOGS=()

join_by() {
  local separator="$1"; shift
  local first=1 item
  for item in "$@"; do
    if [[ "$first" == "1" ]]; then printf '%s' "$item"; first=0
    else printf '%s%s' "$separator" "$item"; fi
  done
}

run_build() {
  local name="$1" logfile="$2"; shift 2
  local started ended status cmd_string
  cmd_string="$*"
  started="$(date +%s)"
  echo "==> [$name] 开始：$cmd_string"
  set +e
  "$@" 2>&1 | tee "$logfile"
  status=${PIPESTATUS[0]}
  set -e
  ended="$(date +%s)"
  BUILD_NAMES+=("$name")
  BUILD_CMDS+=("$cmd_string")
  BUILD_EXITS+=("$status")
  BUILD_SECONDS+=("$((ended - started))")
  BUILD_LOGS+=("$logfile")
  if [[ "$status" -ne 0 ]]; then
    echo "错误：[${name}] 构建失败（退出码 ${status}），日志：${logfile}" >&2
    echo "发行清单未生成；失败日志已保留用于诊断。" >&2
    exit "$status"
  fi
  echo "==> [$name] 完成，耗时 $((ended - started))s"
}

DOCKER_BUILD_EXTRA=()
[[ "$NO_CACHE" == "1" ]] && DOCKER_BUILD_EXTRA+=(--no-cache)

run_build "backend" "$BUILD_LOG_DIR/backend-build.log" \
  docker build -f deploy/Dockerfile.backend \
  ${DOCKER_BUILD_EXTRA[@]+"${DOCKER_BUILD_EXTRA[@]}"} ${EXTRA_BUILD_ARGS[@]+"${EXTRA_BUILD_ARGS[@]}"} \
  --build-arg GIT_SHA="$GIT_SHA" \
  --build-arg BUILD_VERSION="$VERSION" \
  --build-arg BUILD_TIME="$BUILD_TIME" \
  -t "$BACKEND_TAG" .

run_build "frontend" "$BUILD_LOG_DIR/frontend-build.log" \
  docker build -f deploy/Dockerfile.frontend \
  ${DOCKER_BUILD_EXTRA[@]+"${DOCKER_BUILD_EXTRA[@]}"} ${EXTRA_BUILD_ARGS[@]+"${EXTRA_BUILD_ARGS[@]}"} \
  --build-arg GIT_SHA="$GIT_SHA" \
  --build-arg BUILD_VERSION="$VERSION" \
  --build-arg BUILD_TIME="$BUILD_TIME" \
  -t "$FRONTEND_TAG" .

# ---------- 3. 采集镜像与工具链事实 ----------
image_fact() {
  local image="$1" format="$2"
  docker image inspect "$image" --format "$format" 2>/dev/null || echo "unknown"
}

BACKEND_ID="$(image_fact "$BACKEND_TAG" '{{.Id}}')"
FRONTEND_ID="$(image_fact "$FRONTEND_TAG" '{{.Id}}')"
BACKEND_ARCH="$(image_fact "$BACKEND_TAG" '{{.Architecture}}/{{.Os}}')"
FRONTEND_ARCH="$(image_fact "$FRONTEND_TAG" '{{.Architecture}}/{{.Os}}')"
BACKEND_SIZE="$(image_fact "$BACKEND_TAG" '{{.Size}}')"
FRONTEND_SIZE="$(image_fact "$FRONTEND_TAG" '{{.Size}}')"
BACKEND_CREATED="$(image_fact "$BACKEND_TAG" '{{.Created}}')"
FRONTEND_CREATED="$(image_fact "$FRONTEND_TAG" '{{.Created}}')"
BACKEND_REVISION="$(image_fact "$BACKEND_TAG" '{{index .Config.Labels "org.opencontainers.image.revision"}}')"
FRONTEND_REVISION="$(image_fact "$FRONTEND_TAG" '{{index .Config.Labels "org.opencontainers.image.revision"}}')"

# RepoDigest 只有 push/pull 之后才存在；本地镜像以 image ID（内容寻址）作为不可变身份。
BACKEND_DIGEST="$(image_fact "$BACKEND_TAG" '{{if .RepoDigests}}{{index .RepoDigests 0}}{{else}}local:{{.Id}}{{end}}')"
FRONTEND_DIGEST="$(image_fact "$FRONTEND_TAG" '{{if .RepoDigests}}{{index .RepoDigests 0}}{{else}}local:{{.Id}}{{end}}')"

JDK_VERSION="$(docker run --rm --entrypoint sh "$BACKEND_TAG" -c 'java -version 2>&1 | head -1' 2>/dev/null || echo unknown)"
NODE_VERSION="$(docker run --rm --entrypoint sh "$BACKEND_TAG" -c 'node -v' 2>/dev/null || echo unknown)"
NPM_VERSION="$(docker run --rm --entrypoint sh "$BACKEND_TAG" -c 'npm -v' 2>/dev/null || echo unknown)"
NGINX_VERSION="$(docker run --rm --entrypoint sh "$FRONTEND_TAG" -c 'nginx -v 2>&1' 2>/dev/null || echo unknown)"
MAVEN_VERSION="$(grep -m1 -o 'Apache Maven [0-9.][^ ]*' "$BUILD_LOG_DIR/backend-build.log" 2>/dev/null || true)"
MAVEN_VERSION="${MAVEN_VERSION:-unknown}"
DOCKER_VERSION="$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo unknown)"
BUILDKIT_VERSION="$(docker buildx version 2>/dev/null | awk '{print $2}' || echo unknown)"

# ---------- 4. 迁移锁与嵌入资源摘要 ----------
MIGRATION_SUMMARY="$(python3 scripts/check_migrations.py 2>&1)" || {
  echo "错误：迁移校验失败，不生成发行清单：$MIGRATION_SUMMARY" >&2
  exit 1
}
MIGRATION_DIR="OJ-Backend/oj-admin/src/main/resources/db/migration"
MIGRATION_COUNT="$(find "$MIGRATION_DIR" -maxdepth 1 -name 'V*.sql' | wc -l | tr -d ' ')"
MIGRATION_LAST="$(find "$MIGRATION_DIR" -maxdepth 1 -name 'V*.sql' -print | sed 's#.*/##' | sort -V | tail -1)"
MIGRATION_LOCK="OJ-Backend/oj-admin/src/main/resources/db/migration-checksums.json"
if command -v shasum >/dev/null 2>&1; then
  MIGRATION_LOCK_SHA="$(shasum -a 256 "$MIGRATION_LOCK" | awk '{print $1}')"
else
  MIGRATION_LOCK_SHA="$(sha256sum "$MIGRATION_LOCK" | awk '{print $1}')"
fi

# 镜像内 JAR 自检：DatabaseMaintenance 与迁移资源必须随 JAR 发布。
# 运行时镜像没有 unzip/jar 工具；ZIP 中央目录条目名未压缩，直接 grep 二进制即可。
# 期望的“最后一条迁移”来自构建工作树而非写死版本号，避免迁移推进后自检失效。
if [[ -z "${MIGRATION_LAST:-}" ]]; then
  echo "错误：未能确定最后一条迁移文件名，JAR 自检无法进行。" >&2
  exit 1
fi
JAR_CHECK="$(docker run --rm -e EXPECTED_MIGRATION="$MIGRATION_LAST" --entrypoint sh "$BACKEND_TAG" -c '
  f=/app/app.jar
  [ -r "$f" ] || { echo missing-app-jar; exit 0; }
  grep -aq "BOOT-INF/classes/com/musealgo/oj/DatabaseMaintenance.class" "$f" || { echo missing-database-maintenance; exit 0; }
  grep -aq "BOOT-INF/classes/db/migration/${EXPECTED_MIGRATION}" "$f" || { echo missing-latest-migration; exit 0; }
  grep -aq "BOOT-INF/classes/db/migration-checksums.json" "$f" || { echo missing-migration-checksums; exit 0; }
  echo ok
' 2>/dev/null || echo probe-failed)"
if [[ "$JAR_CHECK" != "ok" ]]; then
  echo "警告：镜像内 JAR 自检未通过（结果：${JAR_CHECK}）；请人工核对发行 JAR 内容。" >&2
fi

# ---------- 5. 写发行清单 ----------
export MUSEALGO_MANIFEST="$MANIFEST"
export MUSEALGO_REPO_ROOT="$REPO_ROOT"
export MUSEALGO_GIT_SHA="$GIT_SHA"
export MUSEALGO_GIT_BRANCH="$GIT_BRANCH"
export MUSEALGO_GIT_DIRTY="$GIT_DIRTY"
export MUSEALGO_DIRTY_FILES="$(printf '%s\n' ${DIRTY_FILES[@]+"${DIRTY_FILES[@]}"})"
export MUSEALGO_VERSION="$VERSION"
export MUSEALGO_BUILD_TIME="$BUILD_TIME"
export MUSEALGO_BACKEND_TAG="$BACKEND_TAG"
export MUSEALGO_FRONTEND_TAG="$FRONTEND_TAG"
export MUSEALGO_BACKEND_ID="$BACKEND_ID"
export MUSEALGO_FRONTEND_ID="$FRONTEND_ID"
export MUSEALGO_BACKEND_DIGEST="$BACKEND_DIGEST"
export MUSEALGO_FRONTEND_DIGEST="$FRONTEND_DIGEST"
export MUSEALGO_BACKEND_ARCH="$BACKEND_ARCH"
export MUSEALGO_FRONTEND_ARCH="$FRONTEND_ARCH"
export MUSEALGO_BACKEND_SIZE="$BACKEND_SIZE"
export MUSEALGO_FRONTEND_SIZE="$FRONTEND_SIZE"
export MUSEALGO_BACKEND_CREATED="$BACKEND_CREATED"
export MUSEALGO_FRONTEND_CREATED="$FRONTEND_CREATED"
export MUSEALGO_BACKEND_REVISION="$BACKEND_REVISION"
export MUSEALGO_FRONTEND_REVISION="$FRONTEND_REVISION"
export MUSEALGO_DOCKER="$DOCKER_VERSION"
export MUSEALGO_BUILDKIT="$BUILDKIT_VERSION"
export MUSEALGO_JDK="$JDK_VERSION"
export MUSEALGO_MAVEN="$MAVEN_VERSION"
export MUSEALGO_NODE="$NODE_VERSION"
export MUSEALGO_NPM="$NPM_VERSION"
export MUSEALGO_NGINX="$NGINX_VERSION"
export MUSEALGO_MIGRATION_SUMMARY="$MIGRATION_SUMMARY"
export MUSEALGO_MIGRATION_COUNT="$MIGRATION_COUNT"
export MUSEALGO_MIGRATION_LAST="$MIGRATION_LAST"
export MUSEALGO_MIGRATION_LOCK_SHA="$MIGRATION_LOCK_SHA"
export MUSEALGO_JAR_CHECK="$JAR_CHECK"
export MUSEALGO_BUILD_NAMES="$(join_by '|' ${BUILD_NAMES[@]+"${BUILD_NAMES[@]}"})"
export MUSEALGO_BUILD_CMDS="$(join_by '|' ${BUILD_CMDS[@]+"${BUILD_CMDS[@]}"})"
export MUSEALGO_BUILD_EXITS="$(join_by '|' ${BUILD_EXITS[@]+"${BUILD_EXITS[@]}"})"
export MUSEALGO_BUILD_SECONDS="$(join_by '|' ${BUILD_SECONDS[@]+"${BUILD_SECONDS[@]}"})"
export MUSEALGO_BUILD_LOGS="$(join_by '|' ${BUILD_LOGS[@]+"${BUILD_LOGS[@]}"})"

python3 - <<'PYEOF'
import hashlib
import json
import os
from pathlib import Path

env = os.environ
root = Path(env["MUSEALGO_REPO_ROOT"])
manifest_path = Path(env["MUSEALGO_MANIFEST"])


def safe_int(value, default=0):
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return default


def pipe_items(name):
    value = env.get(name, "")
    return value.split("|") if value else []


migration_dir = root / "OJ-Backend/oj-admin/src/main/resources/db/migration"
migration_files = sorted(
    (p.name for p in migration_dir.iterdir() if p.suffix.lower() == ".sql" and p.name.startswith("V")),
    key=lambda name: int(name.split("__", 1)[0][1:]),
)
lock_path = root / "OJ-Backend/oj-admin/src/main/resources/db/migration-checksums.json"

names = pipe_items("MUSEALGO_BUILD_NAMES")
commands = pipe_items("MUSEALGO_BUILD_CMDS")
exits = pipe_items("MUSEALGO_BUILD_EXITS")
seconds = pipe_items("MUSEALGO_BUILD_SECONDS")
logs = pipe_items("MUSEALGO_BUILD_LOGS")

manifest = {
    "format": 1,
    "kind": "musealgo-oj-release-manifest",
    "built_at": env.get("MUSEALGO_BUILD_TIME", ""),
    "git": {
        "head": env.get("MUSEALGO_GIT_SHA", ""),
        "branch": env.get("MUSEALGO_GIT_BRANCH", ""),
        "dirty": env.get("MUSEALGO_GIT_DIRTY", "0") == "1",
        "dirty_files": [line.strip() for line in env.get("MUSEALGO_DIRTY_FILES", "").splitlines() if line.strip()],
    },
    "version": env.get("MUSEALGO_VERSION", ""),
    "images": {
        "backend": {
            "tag": env.get("MUSEALGO_BACKEND_TAG", ""),
            "id": env.get("MUSEALGO_BACKEND_ID", ""),
            "digest": env.get("MUSEALGO_BACKEND_DIGEST", ""),
            "architecture": env.get("MUSEALGO_BACKEND_ARCH", ""),
            "size_bytes": safe_int(env.get("MUSEALGO_BACKEND_SIZE")),
            "created": env.get("MUSEALGO_BACKEND_CREATED", ""),
            "oci_annotation_revision": env.get("MUSEALGO_BACKEND_REVISION", ""),
        },
        "frontend": {
            "tag": env.get("MUSEALGO_FRONTEND_TAG", ""),
            "id": env.get("MUSEALGO_FRONTEND_ID", ""),
            "digest": env.get("MUSEALGO_FRONTEND_DIGEST", ""),
            "architecture": env.get("MUSEALGO_FRONTEND_ARCH", ""),
            "size_bytes": safe_int(env.get("MUSEALGO_FRONTEND_SIZE")),
            "created": env.get("MUSEALGO_FRONTEND_CREATED", ""),
            "oci_annotation_revision": env.get("MUSEALGO_FRONTEND_REVISION", ""),
        },
    },
    "toolchain": {
        "docker_server": env.get("MUSEALGO_DOCKER", ""),
        "buildkit": env.get("MUSEALGO_BUILDKIT", ""),
        "maven": env.get("MUSEALGO_MAVEN", ""),
        "jdk": env.get("MUSEALGO_JDK", ""),
        "node": env.get("MUSEALGO_NODE", ""),
        "npm": env.get("MUSEALGO_NPM", ""),
        "nginx": env.get("MUSEALGO_NGINX", ""),
    },
    "migrations": {
        "count": safe_int(env.get("MUSEALGO_MIGRATION_COUNT"), len(migration_files)),
        "first": migration_files[0] if migration_files else None,
        "last": env.get("MUSEALGO_MIGRATION_LAST") or (migration_files[-1] if migration_files else None),
        "checksums_sha256": env.get("MUSEALGO_MIGRATION_LOCK_SHA") or (
            hashlib.sha256(lock_path.read_bytes()).hexdigest() if lock_path.is_file() else None
        ),
        "checker": env.get("MUSEALGO_MIGRATION_SUMMARY", "").strip(),
    },
    "jar_check": {"embedded_classes_and_migrations": env.get("MUSEALGO_JAR_CHECK", "")},
    "build_commands": [
        {
            "name": name,
            "command": commands[index] if index < len(commands) else "",
            "exit": safe_int(exits[index] if index < len(exits) else None),
            "duration_s": safe_int(seconds[index] if index < len(seconds) else None),
            "log": logs[index] if index < len(logs) else "",
        }
        for index, name in enumerate(names)
    ],
    "notes": [
        "镜像只保存在本地，脚本不执行 push；digest 字段在未 push 时写 local:<image-id>，实际不可变身份见 images.*.id。",
        "镜像 OCI 标签 org.opencontainers.image.revision 必须等于 git.head；两者同时记录用于交叉核对。",
        "本清单只代表构建机架构（images.*.architecture）；跨架构/目标 Linux 验收另行记录。",
    ],
    "unverified": [
        "目标 Linux x86_64 原生冷安装/升级（REL-02，blocked-pending-environment）",
        "容器冷启动、权限边界与持久化冒烟（由发行验证步骤单独记录证据）",
        "push / PR / 远端 CI（本脚本明确不执行）",
    ],
}

manifest_path.parent.mkdir(parents=True, exist_ok=True)
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(manifest_path)
PYEOF

echo
echo "==> 发行清单已写入：$MANIFEST"
echo "    后端镜像：$BACKEND_TAG ($BACKEND_ID)"
echo "    前端镜像：$FRONTEND_TAG ($FRONTEND_ID)"
echo "    JAR 自检：$JAR_CHECK"
echo "    迁移：${MIGRATION_COUNT} 个，最后 ${MIGRATION_LAST}，锁清单 SHA-256 ${MIGRATION_LOCK_SHA}"
echo
echo "提示：本脚本不 push 镜像。发行记录需同时保存："
echo "  1) 提交 SHA 与镜像 OCI label org.opencontainers.image.revision"
echo "  2) 发行清单（镜像 ID/架构、迁移锁摘要、工具链版本）"
echo "  3) 冷启动/权限/持久化冒烟证据（单独执行并保存）"
