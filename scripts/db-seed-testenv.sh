#!/bin/sh
# 补充开发/测试环境的演示数据。
#
# 与 scripts/db-seed.sh 的区别：
#   db-seed.sh    面向全新数据库，导入初始演示数据（OJ-Backend/db/seed.sql）；
#   本脚本        面向已经在用的开发库，补齐测试所需的规模与完整度数据，
#                幂等、可重复执行，只写入固定 id 段（用户 1001+、提交 10001+ 等）。
#
# 用法：
#   ./scripts/db-seed-testenv.sh              # 生成并写入
#   ./scripts/db-seed-testenv.sh --dry-run    # 只生成 SQL，不写库
#
# 可覆盖的变量：
#   OJ_DB_NAME        数据库名，默认 oj_db
#   OJ_DB_PASS        MySQL root 口令，默认 123456（开发默认值）
#   OJ_MYSQL_CONTAINER MySQL 容器名，默认 musealgo-mysql

set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export OJ_DB_NAME="${OJ_DB_NAME:-oj_db}"
export OJ_DB_PASS="${OJ_DB_PASS:-123456}"
export OJ_MYSQL_CONTAINER="${OJ_MYSQL_CONTAINER:-musealgo-mysql}"

if ! docker info >/dev/null 2>&1; then
  echo "Docker 守护进程不可用，请先启动 Docker Desktop。" >&2
  exit 1
fi

if ! docker ps --format '{{.Names}}' | grep -qx "$OJ_MYSQL_CONTAINER"; then
  echo "MySQL 容器 $OJ_MYSQL_CONTAINER 未运行，请先执行：" >&2
  echo "  docker compose -f compose.dev.yml up -d mysql" >&2
  exit 1
fi

if [ "$1" = "--dry-run" ]; then
  python3 "$ROOT/scripts/seed-testenv.py" --generate-only
  echo "已生成 $ROOT/OJ-Backend/db/seed-testenv.sql，未写入数据库。"
  exit 0
fi

python3 "$ROOT/scripts/seed-testenv.py"

echo "完成。测试账号：stu1001 ~ stu1050，口令均为 123456，邮箱域 .test.local。"
echo "注意：这些账号与数据仅供本地开发/测试，不要带入生产环境。"
