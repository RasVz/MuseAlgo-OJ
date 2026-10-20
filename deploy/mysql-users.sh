#!/bin/bash
# Executed only on a NEW production MySQL volume. For existing installations execute this mounted script after configuring the current root password.
set -euo pipefail
: "${OJ_DB_PASS:?required}"
: "${MYSQL_PASSWORD:?required}"
# Restrict the application password to generated hex so no credentials can become SQL syntax.
if [[ ! "$OJ_DB_PASS" =~ ^[a-fA-F0-9]{32,}$ || ! "$MYSQL_PASSWORD" =~ ^[a-fA-F0-9]{32,}$ ]]; then
  echo 'OJ_DB_PASS must contain at least 32 hexadecimal characters; generate with openssl rand -hex 32' >&2
  exit 1
fi
if [[ "$OJ_DB_PASS" == "$MYSQL_PASSWORD" || "$OJ_DB_PASS" == "$MYSQL_ROOT_PASSWORD" || "$MYSQL_PASSWORD" == "$MYSQL_ROOT_PASSWORD" ]]; then
  echo 'Application, migration and root passwords must be distinct' >&2
  exit 1
fi
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<SQL
CREATE USER IF NOT EXISTS 'musealgo_migrator'@'%' IDENTIFIED BY '$MYSQL_PASSWORD';
ALTER USER 'musealgo_migrator'@'%' IDENTIFIED BY '$MYSQL_PASSWORD';
GRANT ALL PRIVILEGES ON oj_db.* TO 'musealgo_migrator'@'%';
CREATE USER IF NOT EXISTS 'musealgo_app'@'%' IDENTIFIED BY '$OJ_DB_PASS';
ALTER USER 'musealgo_app'@'%' IDENTIFIED BY '$OJ_DB_PASS';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'musealgo_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON oj_db.* TO 'musealgo_app'@'%';
SQL
