#!/bin/sh
# MuseAlgo-OJ 本地启动：加载仓库根目录的 .env，先安装模块再启动 oj-admin。
# 用法：./scripts/run-local.sh（只在自备本机 MySQL 8 / Redis 的开发环境使用）
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ ! -f .env ]; then
    printf '%s\n' '错误：仓库根目录缺少 .env（可 cp .env.example .env 后填写）' >&2
    exit 1
fi

set -a
# shellcheck disable=SC1091
. ./.env
set +a

printf '%s\n' '== 启动前自检 =='
if [ -n "${DEEPSEEK_API_KEY:-}" ]; then
    printf '%s\n' '  DEEPSEEK_API_KEY = 已设置'
else
    printf '%s\n' '  DEEPSEEK_API_KEY = 未设置（AI 将降级）'
fi
if [ -z "${JWT_SECRET:-}" ] || [ -z "${OJ_DB_PASS:-}" ]; then
    printf '%s\n' '错误：本地启动需在 .env 中设置 JWT_SECRET 和 OJ_DB_PASS' >&2
    exit 1
fi
printf '%s\n' '  JWT_SECRET       = 已设置' '  OJ_DB_PASS       = 已设置' '=================='

cd "$ROOT/OJ-backend"
./mvnw -pl oj-admin -am -DskipTests install
exec ./mvnw -pl oj-admin spring-boot:run
