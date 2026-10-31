#!/bin/bash
# Executed only on a NEW production MySQL volume. For existing installations execute this mounted script after configuring the current root password.
# Verification-only mode (existing installations): MUSEALGO_DB_USERS_VERIFY_ONLY=1 ./10-users.sh
# assigns no grants and only asserts the current grant set matches the intended boundary; exit 1 on drift.
set -euo pipefail
: "${OJ_DB_PASS:?required}"
: "${MYSQL_PASSWORD:?required}"
: "${MYSQL_ROOT_PASSWORD:?required}"
VERIFY_ONLY="${MUSEALGO_DB_USERS_VERIFY_ONLY:-0}"

# Restrict the application password to generated hex so no credentials can become SQL syntax.
if [[ ! "$OJ_DB_PASS" =~ ^[a-fA-F0-9]{32,}$ || ! "$MYSQL_PASSWORD" =~ ^[a-fA-F0-9]{32,}$ ]]; then
  echo 'OJ_DB_PASS must contain at least 32 hexadecimal characters; generate with openssl rand -hex 32' >&2
  exit 1
fi
if [[ "$OJ_DB_PASS" == "$MYSQL_PASSWORD" || "$OJ_DB_PASS" == "$MYSQL_ROOT_PASSWORD" || "$MYSQL_PASSWORD" == "$MYSQL_ROOT_PASSWORD" ]]; then
  echo 'Application, migration and root passwords must be distinct' >&2
  exit 1
fi

if [[ "$VERIFY_ONLY" != "1" ]]; then
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<SQL
CREATE USER IF NOT EXISTS 'musealgo_migrator'@'%' IDENTIFIED BY '$MYSQL_PASSWORD';
ALTER USER 'musealgo_migrator'@'%' IDENTIFIED BY '$MYSQL_PASSWORD';
GRANT ALL PRIVILEGES ON oj_db.* TO 'musealgo_migrator'@'%';
CREATE USER IF NOT EXISTS 'musealgo_app'@'%' IDENTIFIED BY '$OJ_DB_PASS';
ALTER USER 'musealgo_app'@'%' IDENTIFIED BY '$OJ_DB_PASS';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'musealgo_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON oj_db.* TO 'musealgo_app'@'%';
SQL
fi

# ---------- 收敛校验：账号权限必须与设计边界一致，否则立即失败 ----------
# 应用账号：只允许 oj_db.* 的 SELECT/INSERT/UPDATE/DELETE。
# 迁移账号：只允许 oj_db.* 的全部权限（无全局授权）。
# MySQL 对无全局权限的账号会始终回显一行 "GRANT USAGE ON *.* TO ..."，那是「无权限」的占位而非授权。
# MySQL 8 的 SHOW GRANTS 会给库名/账号加反引号，并把库名里的下划线转义成 oj\_db；
# 官方 entrypoint 在 MYSQL_DATABASE+MYSQL_USER 预建账号时会先授一次 ALL，随后本脚本再授一次等价语句，
# 因此真实环境可能返回两行等价授权（库名分别带/不带反斜杠转义），归一化后必须都能识别。
# 任何多余授权、GRANT OPTION 或全局权限都会让脚本 exit 1，避免「以为收紧了、实际带 DDL 上线」。
verify_user() {
  local user="$1" expected="$2" raw line seen=0
  if ! raw="$(MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --skip-column-names -e "SHOW GRANTS FOR '$user'@'%'")"; then
    echo "Unable to read grants for $user@%" >&2
    exit 1
  fi
  if [[ -z "$raw" ]]; then
    echo "No grants returned for $user@% (does the account exist?)" >&2
    exit 1
  fi
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    line="$(printf '%s' "$line" | tr -d "\140\134")"   # 去掉反引号与转义反斜杠后做字面比较
    if [[ "$line" == "GRANT USAGE ON *.* TO $user@%" ]]; then
      continue
    fi
    if [[ "$line" == "$expected" ]]; then
      seen=$((seen + 1))
      continue
    fi
    echo "Unexpected grant for $user@%: $line" >&2
    exit 1
  done <<< "$raw"
  # 等价授权行允许 1-2 行（官方 entrypoint 预建授权 + 本脚本授权）；再多的等价行同样视为权限漂移。
  if [[ "$seen" -lt 1 || "$seen" -gt 2 ]]; then
    echo "Grant boundary mismatch for $user@%: expected 1-2 equivalent [$expected] line(s), found $seen" >&2
    exit 1
  fi
  echo "Grant boundary OK for $user@%: $expected"
}

verify_user 'musealgo_app' 'GRANT SELECT, INSERT, UPDATE, DELETE ON oj_db.* TO musealgo_app@%'
verify_user 'musealgo_migrator' 'GRANT ALL PRIVILEGES ON oj_db.* TO musealgo_migrator@%'
