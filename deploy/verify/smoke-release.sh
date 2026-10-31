#!/usr/bin/env bash
# ============================================================
# MuseAlgo-OJ 发行镜像冷启动冒烟（REL-02，目标机执行）
# 覆盖：镜像内权限边界 / 内部入口隔离 / 静态资源与持久化 / 卷属主 / 重启恢复
# 只读为主：不删除数据、不改库；唯一写操作是在 uploads 卷写一个临时探针文件。
# 用法：
#   set -a; . ./deploy/verify/env.verify; set +a
#   ./deploy/verify/smoke-release.sh
# 依赖：bash、docker compose v2、curl
# ============================================================
set -uo pipefail

COMPOSE_FILE="${REL02_COMPOSE_FILE:-docker-compose.prod.yml}"
PROJECT="${REL02_PROJECT:?REL02_PROJECT 必须设置（随机项目名）}"
ENV_FILE="${REL02_ENV_FILE:-.env}"
BASE_URL="${REL02_BASE_URL:-http://127.0.0.1}"
BACKEND_URL="${REL02_BACKEND_URL:-http://127.0.0.1:8080}"
EVIDENCE_DIR="${REL02_EVIDENCE_DIR:-$HOME/musealgo-rel02-evidence}"
BACKEND_IMAGE="${REL02_BACKEND_IMAGE:-}"
FRONTEND_IMAGE="${REL02_FRONTEND_IMAGE:-}"
OUT="$EVIDENCE_DIR/smoke-release"

mkdir -p "$OUT"
chmod 700 "$EVIDENCE_DIR" "$OUT" 2>/dev/null || true

PASS=0
FAIL=0
: > "$OUT/result.txt"

ok()   { PASS=$((PASS+1)); printf "  [PASS] %s\n" "$1" | tee -a "$OUT/result.txt"; }
bad()  { FAIL=$((FAIL+1)); printf "  [FAIL] %s\n" "$1" | tee -a "$OUT/result.txt"; }
info() { printf "  [INFO] %s\n" "$1" | tee -a "$OUT/result.txt"; }

printf "# smoke-release %s project=%s\n" "$(date -Is)" "$PROJECT" | tee -a "$OUT/result.txt"

compose() { docker compose -f "$COMPOSE_FILE" -p "$PROJECT" --env-file "$ENV_FILE" "$@"; }

# ---------- 0. 前置 ----------
printf "## 0. 前置\n" | tee -a "$OUT/result.txt"
if [ -z "$BACKEND_IMAGE" ] || [ -z "$FRONTEND_IMAGE" ]; then
  bad "REL02_BACKEND_IMAGE / REL02_FRONTEND_IMAGE 未设置"
else
  ok "镜像变量已设置"
fi
compose ps > "$OUT/compose-ps.txt" 2>&1 || true
if grep -q "Up" "$OUT/compose-ps.txt"; then ok "compose 项目正在运行"; else bad "compose 项目未运行"; fi

# ---------- 1. 镜像内权限边界 ----------
printf "## 1. 镜像内权限边界\n" | tee -a "$OUT/result.txt"

if [ -n "$BACKEND_IMAGE" ]; then
  docker image inspect "$BACKEND_IMAGE" > "$OUT/backend-inspect.json" 2>/dev/null || true
  U=$(docker image inspect "$BACKEND_IMAGE" --format "{{.Config.User}}" 2>/dev/null || true)
  if [ "$U" = "musealgo" ]; then ok "后端镜像 User=musealgo"; else bad "后端镜像 User=$U（期望 musealgo）"; fi

  docker run --rm --entrypoint sh "$BACKEND_IMAGE" -c 'id; echo ---; if test -w /app/app.jar; then echo APPJAR_WRITABLE; else echo APPJAR_READONLY; fi; for d in /app/logs /app/OJ-Coursewares /app/OJ-Slidev /data/testcases /data/uploads; do if test -d "$d" && touch "$d/.smoke-probe" 2>/dev/null; then rm -f "$d/.smoke-probe"; echo "DIR_OK $d"; else echo "DIR_BAD $d"; fi; done' > "$OUT/backend-perms.txt" 2>&1 || true
  if grep -q "APPJAR_READONLY" "$OUT/backend-perms.txt"; then ok "app.jar 以默认用户不可写"; else bad "app.jar 权限不符（见 backend-perms.txt）"; fi
  if grep -q "uid=10001" "$OUT/backend-perms.txt"; then ok "后端运行 uid=10001"; else bad "后端运行 uid 非 10001"; fi
  for d in /app/logs /app/OJ-Coursewares /app/OJ-Slidev /data/testcases /data/uploads; do
    if grep -q "DIR_OK $d" "$OUT/backend-perms.txt"; then ok "目录存在且可写 $d"; else bad "目录缺失或不可写 $d"; fi
  done

  docker run --rm --entrypoint sh "$BACKEND_IMAGE" -c 'echo APPJAR_BYTES=$(stat -c %s /app/app.jar 2>/dev/null); echo APPJAR_MODE=$(stat -c %a /app/app.jar 2>/dev/null); ls -ln /app | head -20; ls -la /app/OJ-Slidev | head -10' > "$OUT/backend-files.txt" 2>&1 || true
  if grep -q "APPJAR_MODE=444" "$OUT/backend-files.txt"; then ok "app.jar 权限 0444"; else info "app.jar 权限见 backend-files.txt"; fi

  docker run --rm --entrypoint sh "$BACKEND_IMAGE" -c 'test -f /app/OJ-Slidev/node_modules/.bin/slidev && echo SLIDEV_CLI_OK || echo SLIDEV_CLI_MISSING; command -v node && node --version' > "$OUT/slidev-runtime.txt" 2>&1 || true
  if grep -q "SLIDEV_CLI_OK" "$OUT/slidev-runtime.txt"; then ok "镜像内含 Slidev CLI"; else bad "镜像内缺 Slidev CLI"; fi
fi

if [ -n "$FRONTEND_IMAGE" ]; then
  docker image inspect "$FRONTEND_IMAGE" > "$OUT/frontend-inspect.json" 2>/dev/null || true
  docker run --rm --entrypoint sh "$FRONTEND_IMAGE" -c 'test -f /etc/nginx/conf.d/musealgo.conf && echo CONF_OK || echo CONF_MISSING; grep -c "server_tokens off" /etc/nginx/conf.d/musealgo.conf || true; test -f /usr/share/nginx/html/index.html && echo INDEX_OK || echo INDEX_MISSING' > "$OUT/frontend-config.txt" 2>&1 || true
  if grep -q "CONF_OK" "$OUT/frontend-config.txt"; then ok "前端 nginx 配置已就位"; else bad "前端 nginx 配置缺失"; fi
  if grep -q "INDEX_OK" "$OUT/frontend-config.txt"; then ok "前端静态资源已打包"; else bad "前端静态资源缺失"; fi
  ST=$(sed -n "2p" "$OUT/frontend-config.txt" | tr -dc "0-9")
  if [ -n "$ST" ] && [ "$ST" -gt 0 ]; then ok "server_tokens off"; else bad "frontend nginx 缺 server_tokens off"; fi
fi

# ---------- 2. 健康与入口隔离 ----------
printf "## 2. 健康与入口隔离\n" | tee -a "$OUT/result.txt"

BH=$(curl -fsS -m 10 "$BACKEND_URL/actuator/health" 2>/dev/null || echo "")
printf "%s" "$BH" > "$OUT/backend-health.json"
if printf "%s" "$BH" | grep -q '"status":"UP"'; then ok "后端 /actuator/health UP"; else bad "后端健康检查失败: $BH"; fi

FH=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "$BASE_URL/healthz")
if [ "$FH" = "200" ]; then ok "前端 /healthz 200"; else bad "前端 /healthz = $FH"; fi

IH=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "$BASE_URL/")
if [ "$IH" = "200" ]; then ok "站点首页 200"; else bad "站点首页 = $IH"; fi

printf -- "--- 内部入口隔离（期望全 404）---\n" > "$OUT/entry-isolation.txt"
ISO_FAIL=0
for p in /api/actuator/health /api/actuator/ /api/doc.html /api/v3/api-docs /api/webjars/; do
  C=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "$BASE_URL$p")
  printf "%s -> %s\n" "$p" "$C" >> "$OUT/entry-isolation.txt"
  if [ "$C" != "404" ]; then ISO_FAIL=1; printf "  非 404: %s -> %s\n" "$p" "$C" >> "$OUT/entry-isolation.txt"; fi
done
if [ "$ISO_FAIL" = "0" ]; then ok "5 个内部入口全部 404"; else bad "存在未被屏蔽的内部入口（见 entry-isolation.txt）"; fi

curl -sI -m 10 "$BASE_URL/" > "$OUT/headers.txt" 2>&1 || true
if grep -qi "X-Content-Type-Options: nosniff" "$OUT/headers.txt"; then ok "安全头 nosniff"; else bad "缺 X-Content-Type-Options"; fi
if grep -qi "X-Frame-Options" "$OUT/headers.txt"; then ok "安全头 X-Frame-Options"; else bad "缺 X-Frame-Options"; fi
if grep -qi "Referrer-Policy" "$OUT/headers.txt"; then ok "安全头 Referrer-Policy"; else bad "缺 Referrer-Policy"; fi
if grep -qiE "^Server: nginx/[0-9]" "$OUT/headers.txt"; then bad "Server 头泄露 nginx 版本"; else ok "Server 头无版本信息"; fi

# ---------- 3. 端口暴露 ----------
printf "## 3. 端口暴露\n" | tee -a "$OUT/result.txt"
BIND=$(compose port backend 8080 2>/dev/null || true)
printf "backend -> %s\n" "$BIND" >> "$OUT/ports.txt"
case "$BIND" in
  127.0.0.1:*) ok "后端仅绑 127.0.0.1" ;;
  *) bad "后端端口绑定异常: $BIND" ;;
esac
MP=$(compose port mysql 3306 2>/dev/null || true)
RP=$(compose port redis 6379 2>/dev/null || true)
printf "mysql -> %s\n" "${MP:-<none>}" >> "$OUT/ports.txt"
printf "redis -> %s\n" "${RP:-<none>}" >> "$OUT/ports.txt"
if [ -z "$MP" ]; then ok "MySQL 未发布端口"; else bad "MySQL 发布了端口: $MP"; fi
if [ -z "$RP" ]; then ok "Redis 未发布端口"; else bad "Redis 发布了端口: $RP"; fi

# ---------- 4. 卷与持久化 ----------
printf "## 4. 卷与持久化\n" | tee -a "$OUT/result.txt"
VOLS=$(docker volume ls --filter "label=com.docker.compose.project=$PROJECT" --format "{{.Name}}")
printf "%s\n" "$VOLS" > "$OUT/volumes.txt"
VN=$(printf "%s\n" "$VOLS" | grep -c . || true)
if [ "$VN" = "5" ]; then ok "5 个命名卷存在"; else bad "卷数 = $VN（期望 5）"; fi

printf "musealgo rel02 smoke %s\n" "$(date -Is)" > /tmp/rel02-smoke.txt
docker run --rm -v "${PROJECT}_uploads-data:/d" -v /tmp/rel02-smoke.txt:/src:ro alpine sh -c "cp /src /d/.rel02-smoke && ls -ln /d/.rel02-smoke" > "$OUT/uploads-write.txt" 2>&1 || true

compose exec -T backend sh -c "cat /data/uploads/.rel02-smoke 2>/dev/null" > "$OUT/uploads-backend-read.txt" 2>&1 || true
if grep -q "musealgo rel02 smoke" "$OUT/uploads-backend-read.txt"; then ok "uploads 卷后端可读"; else bad "uploads 卷后端读取失败"; fi

compose restart > "$OUT/restart.txt" 2>&1 || true
sleep 25
compose ps > "$OUT/restart-ps.txt" 2>&1 || true
BH2=$(curl -fsS -m 15 "$BACKEND_URL/actuator/health" 2>/dev/null || echo "")
printf "%s" "$BH2" > "$OUT/backend-health-after-restart.json"
if printf "%s" "$BH2" | grep -q '"status":"UP"'; then ok "重启后后端仍 UP"; else bad "重启后后端不健康"; fi
R2=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "$BASE_URL/healthz")
if [ "$R2" = "200" ]; then ok "重启后前端 /healthz 200"; else bad "重启后前端 /healthz = $R2"; fi

compose exec -T backend sh -c "cat /data/uploads/.rel02-smoke 2>/dev/null" > "$OUT/uploads-backend-read-after-restart.txt" 2>&1 || true
if grep -q "musealgo rel02 smoke" "$OUT/uploads-backend-read-after-restart.txt"; then ok "重启后 uploads 数据仍在"; else bad "重启后 uploads 数据丢失"; fi

# ---------- 5. 日志目录 ----------
printf "## 5. 日志\n" | tee -a "$OUT/result.txt"
compose exec -T backend sh -c "ls -ln /app/logs 2>/dev/null | head -10" > "$OUT/app-logs.txt" 2>&1 || true
if grep -qE "oj-.*\.json\.log" "$OUT/app-logs.txt"; then ok "logback 已写入 /app/logs"; else info "未发现日志文件（可能尚未滚动，见 app-logs.txt）"; fi

# ---------- 汇总 ----------
printf "\n" | tee -a "$OUT/result.txt"
printf "汇总：PASS=%s FAIL=%s\n" "$PASS" "$FAIL" | tee -a "$OUT/result.txt"
if [ "$FAIL" -gt 0 ]; then
  printf "结论：FAIL（存在未通过项，不得上线）\n" | tee -a "$OUT/result.txt"
  exit 1
fi
printf "结论：PASS（冒烟通过；不等于完整 REL-02 验收通过）\n" | tee -a "$OUT/result.txt"
exit 0
