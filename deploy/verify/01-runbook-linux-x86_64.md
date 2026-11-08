# 01 · 原生 Linux x86_64 冷安装验收 Runbook（REL-02）

状态：**blocked-pending-environment（未执行）**。本文件在目标机执行时逐节填写；未执行的节保持 pending。

## 0. 适用范围

本 runbook 覆盖路线图 REL-02 的全部场景：原生 Linux x86_64 冷安装 → 参考数据 → 迁移 → 核心流程、
旧版合成库升级、新项目恢复、Judge0 语言与 AC/WA/CE/TLE/内存边界、重启恢复。

不在本 runbook 范围：管理端浏览器回归（QA-02）、发行镜像构建（REL-01）、远端推送/PR（REL-03）。

约定：
- 所有命令默认在**仓库根目录**执行（MuseAlgo-OJ）。
- 所有变量来自私有 env.verify（chmod 600）；下文用 `set -a; . ./deploy/verify/env.verify; set +a` 载入。
- 每个命令记录：命令、退出码、关键输出。证据写入 `${REL02_EVIDENCE_DIR}`。
- 任一节失败：保留现场（`docker compose -f ... ps -a`、容器日志、DB 状态），在 02-checklist.md 记 failed，
  不要盲目 `down -v` 或重跑迁移。

## 1. 主机基线与前置检查

```bash
uname -m                       # 期望 x86_64
uname -a
cat /etc/os-release | head -5  # 记录发行版与版本
docker version --format "{{.Server.Version}} / {{.Server.Arch}}"   # 期望 amd64
docker compose version
free -h; df -h /; nproc
```

判定：
- `uname -m` 必须是 `x86_64`；`docker info` 的 `Architecture` 必须是 `x86_64`。arm64 + 模拟层**不满足**本验收。
- 内存建议 >=8GB（Compose 五服务 + Judge0 另机）；磁盘 >=40GB。
- 记录 Docker Server/Client、Compose、内核、发行版到 `evidence/01-host.txt`。

端口占用检查（默认 80/8080；MySQL/Redis 不发布端口）：

```bash
ss -ltnp | grep -E ":(80|8080)\b" || true
```

## 2. 发行物与版本绑定（来自 REL-01 清单）

```bash
docker image inspect "${REL02_BACKEND_IMAGE}"  --format "{{.Id}} {{index .Config.Labels \"org.opencontainers.image.revision\"}}"
docker image inspect "${REL02_FRONTEND_IMAGE}" --format "{{.Id}} {{index .Config.Labels \"org.opencontainers.image.revision\"}}"
docker image inspect "${REL02_BACKEND_IMAGE}" --format "{{.Config.User}} {{.Config.Healthcheck.Test}}"
```

判定：
- 两个镜像都存在，`org.opencontainers.image.revision` == `${REL02_CANDIDATE_SHA}`。
- 后端 `.Config.User` == `musealgo`；Healthcheck 非空。
- 把 `docker image inspect` 完整输出存 `evidence/02-images.json`。
- 如果镜像是从别处拷贝的 tar：`docker load -i <tar>` 后仍要核对 revision 与 digest/ID 是否与 REL-01 清单一致。

## 3. 私有 env 与冷启动（全新环境、全新项目）

3.1 准备私有 env（**目标机本地输入口令，不要经过对话/仓库**）：

```bash
cd <仓库根目录>
cp .env.example .env
chmod 600 .env
# 必填：JWT_SECRET(>=32字节) OJ_DB_PASS OJ_DB_ROOT_PASS OJ_DB_MIGRATION_PASS（三者互不相同，32位hex）
# 必填钉镜像：BACKEND_IMAGE / FRONTEND_IMAGE = ${REL02_BACKEND_IMAGE} / ${REL02_FRONTEND_IMAGE}
# 建议：WEB_PORT=80，BACKEND_BIND=127.0.0.1，BACKEND_PORT=8080
# 判题：JUDGE0_URLS=<Judge0 地址>，OJ_PUBLIC_BASE_URL 显式留空（同源相对）
```

生成口令的命令（在目标机执行，输出只留在本地）：

```bash
openssl rand -hex 32   # OJ_DB_PASS / OJ_DB_ROOT_PASS / OJ_DB_MIGRATION_PASS 各生成一次，确保互异
openssl rand -base64 48 # JWT_SECRET
```

3.2 首次启动（空数据卷）：

```bash
docker compose -f "${REL02_COMPOSE_FILE}" -p "${REL02_PROJECT}" --env-file .env up -d
docker compose -f "${REL02_COMPOSE_FILE}" -p "${REL02_PROJECT}" ps -a
```

期望的启动顺序（这是 REL-01/REL-02 的核心验收点）：
1. `mysql` 在空卷上执行 `docker/mysql-init/00-schema.sql` → `01-reference-roles.sql` → `deploy/mysql-users.sh`，
   创建 `musealgo_migrator` 与 `musealgo_app` 两个账号，然后进入 healthy。
2. `migrate` 容器以迁移账号执行 Flyway，把 V54..V63 应用到 V53 基线之上，**退出码 0**。
   注意：migrate 退出码 0 是正常现象，不是崩溃。
3. `backend` 以 `musealgo_app` 启动，`/actuator/health` UP。
4. `frontend` 启动，`/healthz` 返回 200。

3.3 采集启动证据：

```bash
docker compose -f "${REL02_COMPOSE_FILE}" -p "${REL02_PROJECT}" ps -a > "${REL02_EVIDENCE_DIR}/03-compose-ps.txt"
docker inspect --format "{{.Name}} exit={{.State.ExitCode}} status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{end}}" \
  $(docker compose -f "${REL02_COMPOSE_FILE}" -p "${REL02_PROJECT}" ps -aq) > "${REL02_EVIDENCE_DIR}/03-container-states.txt"
curl -fsS "${REL02_BACKEND_URL}/actuator/health"
curl -fsS -o /dev/null -w "%{http_code}\n" "${REL02_BASE_URL}/healthz"
curl -fsS -o /dev/null -w "%{http_code}\n" "${REL02_BASE_URL}/"
```

期望：后端 health `{"status":"UP"}`；`/healthz` 200；站点首页 200。

3.4 迁移与参考数据核对（在 mysql 容器内用 root）：

```bash
docker compose -f "${REL02_COMPOSE_FILE}" -p "${REL02_PROJECT}" exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B oj_db -e "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1; SELECT MAX(version) FROM flyway_schema_history; SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=\'oj_db\';"'
```

期望：成功迁移记录数 = 63（V1..V63 全部 success=1；其中 V1..V48 来自 00-schema.sql 的基线历史，V49..V63 由 migrate 应用）；
`MAX(version)` = `63`；表数 = 67（59 基线表 + V54..V63 静态新增 8 表）。
更严格的核对以 `docker/mysql-init/README.md` 与 `tests/database_reference_smoke.py` 的断言为准。

参考数据核对：

```sql
SELECT id, code FROM sys_role ORDER BY id;                    -- 1:STUDENT,2:TEACHER,3:ADMIN
SELECT code FROM sys_permission ORDER BY code;                -- 7 项（V56/V60 后）
SELECT COUNT(*) FROM user;                                    -- 必须是 0（不得有默认管理员）
```

## 4. 首个管理员创建（无演示数据路径）

1) 通过真实注册接口创建本人账号（不要在数据库里手插用户）：

```bash
curl -fsS -X POST "${REL02_BACKEND_URL}/auth/register" -H "Content-Type: application/json" \
  -d '{"username":"<你的账号>","password":"<强口令>","nickname":"<昵称>","email":"<邮箱>"}'
```

2) DBA 在容器内核对（双重核对 id 与 username）：

```bash
docker compose -f "${REL02_COMPOSE_FILE}" -p "${REL02_PROJECT}" exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot oj_db -e "SELECT id,username,role FROM user;"'
```

3) 只提升**已核对的那一个 id**，且要求当前 role=1：

```sql
UPDATE user SET role=3 WHERE id=<已核对数字ID> AND role=1;
```

4) 重新登录（旧 token 权限不变），进入后台确认管理入口可用；后续角色一律走 `POST /admin/users/{id}/role`。

判定：
- 注册前后 `SELECT COUNT(*) FROM user` 增加 1，无其他账号；
- 提升只影响目标 id；`UPDATE` 的 affected rows 必须是 1；
- 不在 V63 迁移或任何初始化脚本中出现默认管理员（核对 `grep -ri admin docker/mysql-init` 无账号插入）。

## 5. 核心流程（真实 API + 真实 MySQL + 真实 Judge0）

前置：Judge0 已按 docs/DEPLOYMENT.md 第五节部署，`.env` 的 `JUDGE0_URLS` 指向它，后端已重启生效。

用 `deploy/verify/synthetic/prepare_synthetic_data.py` 生成：1 个管理员、1 个教师、2 个学生、
1 个班级（学生加入）、2 道题目（各带 2 组测试用例）、1 场比赛（可选）。脚本只调用公开/正式 API，
不直接写数据库，随机后缀避免污染。

```bash
set -a; . ./deploy/verify/env.verify; set +a
${REL02_PYTHON} deploy/verify/synthetic/prepare_synthetic_data.py \
  --base-url "${REL02_BACKEND_URL}" --evidence-dir "${REL02_EVIDENCE_DIR}/05-synthetic"
```

然后逐项人工/脚本交叉验证（清单见 02-checklist.md 的 CORE 段）：
- 登录/刷新/登出；
- 教师建班 → 学生凭邀请码加入 → 成员列表；
- 教师建题 + 批量保存测试用例 → 学生可见（isPublic=1）；
- 学生提交 -> 判题结果落地（AC 与 WA 各至少一次）；
- 练习统计/提交列表可查；
- 管理员后台用户列表可见合成账号。

注意：`POST /submission/submit` 有 `@RateLimit(submit, 10s, 1次/用户)`；同一账号连续提交必须间隔 >=11 秒，
否则返回业务码 429（HTTP 仍是 200）。脚本已内置间隔。

## 6. 旧版合成库升级（V62 → 当前）

依赖 REL-01 产物之外的测试 fixture 镜像（由当前候选 JAR 派生，非发行物）：

```bash
# 1) 从后端镜像取 JAR
C=$(docker create "${REL02_BACKEND_IMAGE}") && docker cp "$C:/app/app.jar" /tmp/rel02-app.jar && docker rm "$C"
# 2) 生成 v62 fixture（删 V63 迁移）与 legacy fixture（删 DatabaseMaintenance）
${REL02_PYTHON} tests/build_database_fixtures.py --source-jar /tmp/rel02-app.jar \
  --output-dir "${REL02_EVIDENCE_DIR}/06-fixtures" --base-image "${REL02_BACKEND_IMAGE}" \
  > "${REL02_EVIDENCE_DIR}/06-fixtures/fixture-manifest.json"
# 3) 按 manifest 中的 Dockerfile 构建两个 fixture 镜像（命令见 manifest）
# 4) 跑 V63 迁移失败残留 + 恢复演练
${REL02_PYTHON} tests/flyway_failure_smoke.py --backend-image "${REL02_BACKEND_IMAGE}" \
  --output "${REL02_EVIDENCE_DIR}/06-flyway-failure"
```

判定：
- `flyway_failure_smoke.py` 退出码 0；证据目录含每个步骤日志（0600）与结果 JSON；
- 覆盖点：V63 失败残留、失败历史重试被拒、V53 快照 SHA-256 恢复升级；
- 结束时无 `musealgo-flyway-*` 容器/卷/网络残留（脚本 finally 清理后人工 `docker ps -a` 复核）。

真实旧库升级（可选、需用户提供旧版合成库备份）：使用 `scripts/ops.py upgrade` 预览 + `--apply` 流程，
见 docs/OPERATIONS.md「升级与回滚」。禁止对生产库执行。

## 7. 新项目恢复（备份 → 恢复 → 校验）

```bash
${REL02_PYTHON} tests/operations_drill.py \
  --backend-image "${REL02_BACKEND_IMAGE}" \
  --frontend-image "${REL02_FRONTEND_IMAGE}" \
  --v62-backend-image "<v62 fixture 镜像>" \
  --legacy-maintenance-image "<legacy fixture 镜像>" \
  --output "${REL02_EVIDENCE_DIR}/07-operations-drill"
```

判定：退出码 0，`operations-drill.json` 覆盖：新装 → status 只读预览 → 固定镜像 → 备份 → 校验 →
V53→当前升级 → 精确 V62→V63 → **新项目**备份恢复 → 角色 HTTP 矩阵（200/403）。
恢复必须使用全新 project 名与 `--confirm-empty-target`；脚本按要求会自动清理 musealgo-drill-* 资源。

## 8. Judge0 判题矩阵（语言 + 边界）

```bash
${REL02_PYTHON} deploy/verify/synthetic/judge_matrix.py \
  --base-url "${REL02_BACKEND_URL}" \
  --evidence-dir "${REL02_EVIDENCE_DIR}/08-judge" \
  --interval "${REL02_SUBMIT_INTERVAL_SECONDS}"
```

矩阵（每个用例都记录 `status/statusText/runTime/runMemory/errorMessage` 原始 JSON）：

| 用例 | 语言 | 期望 |
|---|---|---|
| AC-JAVA | java | status=1 ACCEPTED，score=100 |
| AC-C | c | status=1 |
| AC-CPP | cpp | status=1 |
| AC-PYTHON | python | status=1 |
| WA | cpp | status=2 WRONG_ANSWER，score=0 |
| CE | cpp | status=3 COMPILATION_ERROR，errorMessage 非空 |
| TLE | cpp | status=5 TIME_LIMIT_EXCEEDED（题目 timeLimit=1000ms，代码死循环） |
| 内存边界 | cpp | status=4 RUNTIME_ERROR 且 runMemory 接近题目 memoryLimit（本题设 128MB，分配 512MB） |

说明：**没有独立的 MLE 状态码**（SubmissionStatus 只有 0/1/2/3/4/5，Judge0 状态 7 归入 RUNTIME_ERROR），
所以内存边界只能以 RUNTIME_ERROR + runMemory 字段为证据，不得记为"MLE=7"。

判定：8 个用例状态全部符合期望；`runMemory` 单位为 KB；记录 Judge0 版本（`GET ${REL02_JUDGE0_URL}/about`）。

## 9. 权限与内部入口隔离

9.1 数据库账号权限（应用连接不得有 DDL）：

```bash
docker compose -f "${REL02_COMPOSE_FILE}" -p "${REL02_PROJECT}" exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B -e "SHOW GRANTS FOR \'musealgo_app\'@\'%\'; SHOW GRANTS FOR \'musealgo_migrator\'@\'%\';"'
```

期望：`musealgo_app` 只有 `SELECT, INSERT, UPDATE, DELETE ON oj_db.*`；**没有** CREATE/ALTER/DROP/INDEX/REFERENCES/GRANT。
`musealgo_migrator` 有 oj_db.* 的 DDL/DML 但无全局权限（不得出现 `ON *.*` 的 ALL PRIVILEGES）。
若 `musealgo_app` 出现 DDL，判 failed 并阻断上线。

9.2 运行期无 DDL 的旁证：应用账号执行任意 DDL 必须被拒：

```bash
docker compose -f "${REL02_COMPOSE_FILE}" -p "${REL02_PROJECT}" exec -T mysql sh -c \
  'MYSQL_PWD="$OJ_DB_PASS" mysql -umusealgo_app oj_db -e "CREATE TABLE rel02_should_fail(id int);"'
```

期望：非 0 退出码 + `ERROR 1142 ... command denied to user 
'musealgo_app'@'%' for table 'rel02_should_fail'
`。命令失败本身就是 passed 证据。

9.3 内部入口隔离（经前端 Nginx 访问，必须 404）：

```bash
for p in /api/actuator/health /api/actuator/ /api/doc.html /api/v3/api-docs /api/webjars/; do
  printf "%s -> " "$p"; curl -s -o /dev/null -w "%{http_code}\n" "${REL02_BASE_URL}${p}";
done
```

期望全部 404。同时：
- 直连后端 `curl ${REL02_BACKEND_URL}/actuator/health` 应 200（后端只绑 127.0.0.1；若 BACKEND_BIND 暴露公网则算配置错误）；
- MySQL/Redis **不得**有宿主端口映射：`docker compose port mysql 3306` 应为空；
- 前端安全头：`curl -sI ${REL02_BASE_URL}/` 应含 `X-Content-Type-Options: nosniff`、`X-Frame-Options`、`Referrer-Policy`，且无 `Server: nginx/<版本>` 细节（`server_tokens off`）。

9.4 业务权限矩阵（合成账号）：
- 学生访问 `GET /admin/users/list` → HTTP 200 但业务 code 403（禁止只靠 HTTP 状态判断）；
- 教师访问管理接口 → 业务 code 403；
- 管理员 → 业务 code 200；
- 未被授权访问他人私有题目 → 403。

## 10. 持久化与重启恢复

```bash
# 记录卷与容器
docker volume ls --filter label=com.docker.compose.project="${REL02_PROJECT}"
docker compose -f "${REL02_COMPOSE_FILE}" -p "${REL02_PROJECT}" restart
sleep 30
docker compose -f "${REL02_COMPOSE_FILE}" -p "${REL02_PROJECT}" ps
```

判定：
- 重启后 backend/frontend healthy，MySQL 数据仍在（登录合成账号成功）；
- 上传文件：上传一张图片（`POST /file/upload`）后 `restart`，再按返回 URL 拉取仍 200；
- 测试用例：题目用例在 `testcase-data` 卷，重启后仍可判题（再提交一次 AC）；
- 课件：如触发 Slidev 构建，产物落在 `coursewares-data`，重启后 URL 可访问。

10.1 卷内容归属检查（前后端跨 uid 读取）：

```bash
docker run --rm -v "${REL02_PROJECT}_uploads-data:/d:ro" alpine sh -c "ls -ln /d | head"
```

期望：后端写入的文件 owner 为 10001；前端 nginx worker（uid 101）能读（容器内 `wget` 该 URL 200 即可）。

## 11. 备份 / 校验 / 告警探针

```bash
mkdir -p "${REL02_EVIDENCE_DIR}/11-ops" && chmod 700 "${REL02_EVIDENCE_DIR}/11-ops"
${REL02_PYTHON} scripts/ops.py --compose "${REL02_COMPOSE_FILE}" --project "${REL02_PROJECT}" --env-file .env \
  backup --output "${REL02_EVIDENCE_DIR}/11-ops/backup"
${REL02_PYTHON} scripts/ops.py --compose "${REL02_COMPOSE_FILE}" --project "${REL02_PROJECT}" --env-file .env \
  verify --source "${REL02_EVIDENCE_DIR}/11-ops/backup"
${REL02_PYTHON} scripts/ops.py --compose "${REL02_COMPOSE_FILE}" --project "${REL02_PROJECT}" --env-file .env \
  check --backup-root "${REL02_EVIDENCE_DIR}/11-ops/backup" --max-backup-age-hours 26
```

判定：backup 生成四件产物 + manifest（含 images/maintenance_image_ids/files sha256）；verify 全部校验通过；
check 输出 JSON 且退出码 0。告警探针只输出本地 JSON，**不外发**。

## 12. 清理与证据归档

```bash
docker compose -f "${REL02_COMPOSE_FILE}" -p "${REL02_PROJECT}" down -v --remove-orphans
docker ps -a --filter name="${REL02_PROJECT}" ; docker volume ls --filter name="${REL02_PROJECT}" ; docker network ls --filter name="${REL02_PROJECT}"
chmod -R go-rwx "${REL02_EVIDENCE_DIR}"
```

期望：三条过滤命令均无输出；证据目录 700/600。
然后把证据整体复制回工作区 outputs/dev-batch2-2026-10-21/rel02/evidence/（不提交 git）。

## 13. 结果记录

执行完成后在 02-checklist.md 逐项填写，并把总体状态写入 outputs/dev-batch2-2026-10-21/rel02/rel02-result.md：
- 状态：passed / failed / blocked-pending-environment；
- 主机信息、镜像 revision 与 digest、迁移版本与表数；
- 每段结论与证据路径；未执行或未验证项必须显式列出。
