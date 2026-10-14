#!/bin/sh
# 导入演示数据：安装判题用例文件 + 写入数据库种子。
#
# 前置：MySQL 容器已启动，且后端已完成一次 Flyway 迁移（表结构已建好）。
# 用法：./scripts/db-seed.sh
#
# 可覆盖的变量：
#   OJ_DB_NAME        数据库名，默认 oj_db
#   OJ_DB_PASS        MySQL root 口令，默认 123456（开发默认值）
#   COMPOSE_FILE      Compose 文件路径，默认 compose.dev.yml
#   OJ_TESTCASE_PATH  判题用例安装目录（宿主路径），默认 <仓库根>/OJ-backend/data/testcases
#                     需与后端 oj.testcase.base-path 指向同一份目录（开发栈默认即如此）
#   SEED_FORCE        设为 1 时覆盖已存在的用例文件（默认跳过，避免覆盖你自己的数据）
#
# 为什么必须由本脚本安装用例文件：
#   problem_case 表里存的是相对路径（<problemId>/<caseId>/input.txt），真实内容在文件系统上。
#   只执行 seed.sql 而不安装文件，判题会因读不到用例而失败。

set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-compose.dev.yml}"
DB_NAME="${OJ_DB_NAME:-oj_db}"
DB_PASS="${OJ_DB_PASS:-123456}"

SEED_FILE="$ROOT/OJ-backend/db/seed.sql"
TESTCASE_SRC="$ROOT/OJ-backend/db/demo-testcases"
TESTCASE_DST="${OJ_TESTCASE_PATH:-$ROOT/OJ-backend/data/testcases}"

if [ ! -f "$SEED_FILE" ]; then
  echo "找不到种子文件：$SEED_FILE" >&2
  exit 1
fi

if [ ! -d "$TESTCASE_SRC" ]; then
  echo "找不到演示用例目录：$TESTCASE_SRC" >&2
  exit 1
fi

# ---------- 1. 安装判题用例文件（非破坏性：已存在的文件默认跳过）----------
echo "正在安装演示判题用例到 $TESTCASE_DST ……"
mkdir -p "$TESTCASE_DST"

find "$TESTCASE_SRC" -type f | while read -r src; do
  rel="${src#"$TESTCASE_SRC"/}"
  dst="$TESTCASE_DST/$rel"
  if [ -e "$dst" ] && [ "${SEED_FORCE:-0}" != "1" ]; then
    continue
  fi
  mkdir -p "$(dirname "$dst")"
  cp "$src" "$dst"
done

installed=$(find "$TESTCASE_DST" -type f -name '*.txt' | wc -l | tr -d ' ')
echo "用例安装完成：目录中共有 $installed 个用例文件（已存在的文件默认不覆盖，需要覆盖请设置 SEED_FORCE=1）。"

# ---------- 2. 导入数据库种子 ----------
# 注意：`docker compose ps` 在 Docker 守护进程未运行时仍可能返回 0，
# 因此必须先单独确认守护进程可用，否则会在下面的等待循环里空等两分钟。
if ! docker info >/dev/null 2>&1; then
  echo "Docker 守护进程不可用，请先启动 Docker Desktop。" >&2
  exit 1
fi

if ! docker compose -f "$ROOT/$COMPOSE_FILE" ps mysql >/dev/null 2>&1; then
  echo "MySQL 容器未运行，请先执行：" >&2
  echo "  docker compose -f $COMPOSE_FILE up -d" >&2
  exit 1
fi

echo "正在等待 MySQL 就绪……"
i=0
until docker compose -f "$ROOT/$COMPOSE_FILE" exec -T mysql \
        mysqladmin ping -uroot -p"$DB_PASS" --silent >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "$i" -ge 60 ]; then
    echo "等待 MySQL 超时。" >&2
    exit 1
  fi
  sleep 2
done

echo "正在导入演示数据到 $DB_NAME ……"
docker compose -f "$ROOT/$COMPOSE_FILE" exec -T mysql \
  mysql --default-character-set=utf8mb4 -uroot -p"$DB_PASS" "$DB_NAME" <"$SEED_FILE"

echo "完成。演示数据与判题用例均已就绪。"
echo "注意：演示账号（admin / teacher01，口令 123456）仅供本地开发，切勿带入生产环境。"
