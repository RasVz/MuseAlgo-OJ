# 02 · 逐项验收清单（REL-02）

状态：**blocked-pending-environment（全部 pending）**。
执行时把每行 `状态` 改为 passed / failed / blocked / not-applicable，并填证据路径。
**任何一行 failed 或未验证，都不得给出"上线通过"结论。**

## A. 主机与前置

| ID | 检查项 | 命令/方法 | 期望 | 状态 | 证据 |
|---|---|---|---|---|---|
| HOST-01 | 原生 x86_64 | `uname -m`；`docker info --format {{.Architecture}}` | `x86_64` / `x86_64` | pending | evidence/01-host.txt |
| HOST-02 | Linux 发行版与内核 | `cat /etc/os-release`；`uname -r` | 记录即可 | pending | 同上 |
| HOST-03 | Docker/Compose 版本 | `docker version`；`docker compose version` | Engine >=24；Compose v2 | pending | 同上 |
| HOST-04 | 资源 | `free -h`；`df -h /`；`nproc` | 内存 >=8GB；磁盘 >=40GB | pending | 同上 |
| HOST-05 | 端口空闲 | `ss -ltnp \| grep -E ":(80\|8080)"` | 无占用或已规划 | pending | 同上 |
| PRE-01 | REL-01 发行清单存在且绑定候选 SHA | 读 rel01/release-manifest.json | git.head == 候选 SHA | pending | evidence/00-prereq.txt |
| PRE-02 | QA-02 产物存在 | outputs/.../qa02/ | 有验收 JSON/报告 | pending | evidence/00-prereq.txt |
| PRE-03 | 镜像 revision 标签 == 候选 SHA | `docker image inspect` | 一致 | pending | evidence/02-images.json |
| PRE-04 | 后端镜像 User=musealgo、有 HEALTHCHECK | `docker image inspect` | `musealgo` + 非空 | pending | evidence/02-images.json |

## B. 冷启动与参考数据

| ID | 检查项 | 命令/方法 | 期望 | 状态 | 证据 |
|---|---|---|---|---|---|
| COLD-01 | 空卷首次 up -d 五服务 | `docker compose ... up -d` | 无容器崩溃循环 | pending | 03-compose-ps.txt |
| COLD-02 | mysql 初始化执行 00/01/users | `docker compose logs mysql` | 有初始化日志，无 ERROR | pending | 03-mysql-init.log |
| COLD-03 | migrate 以迁移账号应用剩余迁移并退出 0 | `docker inspect` migrate 容器 ExitCode | 0 | pending | 03-container-states.txt |
| COLD-04 | backend 用 musealgo_app 启动并 healthy | `ps`；`curl /actuator/health` | healthy + status UP | pending | 03-backend-health.txt |
| COLD-05 | frontend /healthz 200 | `curl -o /dev/null -w %{http_code} /healthz` | 200 | pending | 03-frontend-health.txt |
| COLD-06 | 站点首页 200 且返回前端资源 | `curl -I /` | 200 + text/html | pending | 03-frontend-health.txt |
| REF-01 | flyway_schema_history 成功记录 63 条 | SQL COUNT | 63 | pending | 03-db-state.txt |
| REF-02 | 迁移最大版本 = 63 | SQL MAX(version) | 63 | pending | 03-db-state.txt |
| REF-03 | 表数 67 | information_schema | 67 | pending | 03-db-state.txt |
| REF-04 | sys_role = 1:STUDENT,2:TEACHER,3:ADMIN | SQL | 三行 | pending | 03-reference-data.txt |
| REF-05 | sys_permission 7 项（V56/V60 后） | SQL | 7 | pending | 03-reference-data.txt |
| REF-06 | user 表 0 行（无默认管理员） | SQL COUNT | 0 | pending | 03-reference-data.txt |
| REF-07 | mysql-init 无账号插入 | `grep -ri "INSERT INTO user" docker/mysql-init` | 无输出 | pending | 03-grep-init.txt |

## C. 首管理员创建

| ID | 检查项 | 命令/方法 | 期望 | 状态 | 证据 |
|---|---|---|---|---|---|
| ADM-01 | 注册接口创建本人账号 | `POST /auth/register` | 业务 code 200 | pending | 04-admin.txt |
| ADM-02 | DBA 核对 id/username | SQL SELECT | 与注册一致 | pending | 04-admin.txt |
| ADM-03 | 仅提升目标 id 且 role 从 1→3 | `UPDATE ... AND role=1` | affected rows = 1 | pending | 04-admin.txt |
| ADM-04 | 重新登录后管理接口 200 | `GET /admin/users/list` | 业务 code 200 | pending | 04-admin.txt |
| ADM-05 | 管理员通过后台分配教师角色 | `POST /admin/users/{id}/role` | "角色已更新" | pending | 04-admin.txt |

## D. 核心流程（真实 API/MySQL/Judge0）

| ID | 检查项 | 命令/方法 | 期望 | 状态 | 证据 |
|---|---|---|---|---|---|
| CORE-01 | 登录/刷新/登出 | `/auth/login` `/auth/refresh` `/auth/logout` | 全部 code 200 | pending | 05-synthetic/summary.json |
| CORE-02 | 教师建班 + 学生凭码加入 + 成员列表 | `/class/create` `/class/joinByCode` `/class/members` | 成员含该学生 | pending | 05-synthetic/summary.json |
| CORE-03 | 教师建题 + 批量保存用例 | `/problem/add` + `/problem/case/batch` | 题 id 与用例数返回 | pending | 05-synthetic/summary.json |
| CORE-04 | 学生提交 AC | `/submission/submit` 轮询 `/submission/detail` | status=1 score=100 | pending | 05-synthetic/summary.json |
| CORE-05 | 学生提交 WA | 同上 | status=2 score=0 | pending | 05-synthetic/summary.json |
| CORE-06 | 提交列表与练习统计可见 | `/submission/list` `/profile/practice/me` | code 200，计数增加 | pending | 05-synthetic/summary.json |
| CORE-07 | 管理员用户列表可见合成账号 | `/admin/users/list` | 含合成账号 | pending | 05-synthetic/summary.json |

## E. 旧库升级与恢复

| ID | 检查项 | 命令/方法 | 期望 | 状态 | 证据 |
|---|---|---|---|---|---|
| UPG-01 | fixture 生成成功 | `tests/build_database_fixtures.py` | exit 0，manifest 含 v62/legacy | pending | 06-fixtures/fixture-manifest.json |
| UPG-02 | V63 失败残留演练 | `tests/flyway_failure_smoke.py` | exit 0 | pending | 06-flyway-failure/ |
| UPG-03 | 失败历史重试被拒绝 | 同上步骤日志 | 明确拒绝且不破坏现场 | pending | 06-flyway-failure/ |
| UPG-04 | V53 快照 SHA-256 恢复升级 | 同上 | 恢复后版本正确 | pending | 06-flyway-failure/ |
| UPG-05 | 无 musealgo-flyway-* 残留 | `docker ps -a`/`volume ls`/`network ls` | 无输出 | pending | 06-flyway-failure/cleanup.txt |
| RST-01 | operations_drill 全流程 | `tests/operations_drill.py` | exit 0 | pending | 07-operations-drill/operations-drill.json |
| RST-02 | 新项目恢复成功且原项目不受影响 | drill JSON | restore ok + 旧项目未变 | pending | 同上 |
| RST-03 | 角色 HTTP 矩阵 200/403 | drill JSON | 全部符合 | pending | 同上 |
| RST-04 | 无 musealgo-drill-* 残留 | `docker ps -a` 等 | 无输出 | pending | 07-operations-drill/cleanup.txt |

## F. Judge0 矩阵

| ID | 检查项 | 期望 | 状态 | 证据 |
|---|---|---|---|---|
| JUDGE-00 | Judge0 可达且版本记录 | `/about` 200 | pending | 08-judge/judge0-about.json |
| JUDGE-01 | Java AC | status=1 score=100 | pending | 08-judge/matrix.json |
| JUDGE-02 | C AC | status=1 | pending | 同上 |
| JUDGE-03 | C++ AC | status=1 | pending | 同上 |
| JUDGE-04 | Python AC | status=1 | pending | 同上 |
| JUDGE-05 | WA | status=2 score=0 | pending | 同上 |
| JUDGE-06 | CE | status=3 且 errorMessage 非空 | pending | 同上 |
| JUDGE-07 | TLE | status=5（题限 1000ms） | pending | 同上 |
| JUDGE-08 | 内存边界 | status=4 且 runMemory 达题限附近（无 MLE 状态码） | pending | 同上 |

## G. 权限与入口隔离

| ID | 检查项 | 命令/方法 | 期望 | 状态 | 证据 |
|---|---|---|---|---|---|
| SEC-01 | musealgo_app 仅 DML | `SHOW GRANTS FOR musealgo_app` | 只有 SELECT/INSERT/UPDATE/DELETE | pending | 09-grants.txt |
| SEC-02 | musealgo_migrator 无全局权限 | 同上 | 无 `ON *.*` ALL | pending | 09-grants.txt |
| SEC-03 | 应用账号执行 DDL 被拒 | CREATE TABLE via app | ERROR 1142 | pending | 09-ddl-denied.txt |
| SEC-04 | 前端 5 个内部入口 404 | curl 列表 | 全 404 | pending | 09-entry-isolation.txt |
| SEC-05 | 后端仅绑 127.0.0.1 | `docker compose port backend 8080` | 127.0.0.1:8080 | pending | 09-entry-isolation.txt |
| SEC-06 | MySQL/Redis 无宿主端口 | `docker compose port mysql 3306`；`redis 6379` | 空 | pending | 09-entry-isolation.txt |
| SEC-07 | 安全头与 server_tokens off | `curl -sI /` | nosniff/X-Frame/Referrer-Policy，无版本号 | pending | 09-headers.txt |
| SEC-08 | 学生访问管理接口业务码 403 | curl + 解析 result.code | 403（HTTP 可能 200） | pending | 09-rbac-matrix.json |
| SEC-09 | 教师访问管理接口业务码 403 | 同上 | 403 | pending | 09-rbac-matrix.json |
| SEC-10 | 权限不足不泄露内部细节 | 响应体 | 通用错误消息 | pending | 09-rbac-matrix.json |

## H. 持久化与重启

| ID | 检查项 | 命令/方法 | 期望 | 状态 | 证据 |
|---|---|---|---|---|---|
| PER-01 | 五卷存在且属本项目 | `docker volume ls --filter label=com.docker.compose.project` | 5 个 | pending | 10-volumes.txt |
| PER-02 | restart 后 backend/frontend healthy | `compose restart` + ps | healthy | pending | 10-restart.txt |
| PER-03 | 重启后登录/数据仍在 | 登录 + 查询 | 成功 | pending | 10-restart.txt |
| PER-04 | 上传文件持久化 | 上传→restart→GET URL | 200 且内容一致 | pending | 10-uploads.txt |
| PER-05 | 测试用例持久化 | restart 后再判题 AC | status=1 | pending | 10-restart.txt |
| PER-06 | 卷文件 owner=10001 且前端可读 | `ls -ln` + nginx 请求 | owner 10001；HTTP 200 | pending | 10-volumes.txt |

## I. 运维与清理

| ID | 检查项 | 命令/方法 | 期望 | 状态 | 证据 |
|---|---|---|---|---|---|
| OPS-01 | backup 四件产物 + manifest | `scripts/ops.py backup` | exit 0 | pending | 11-ops/ |
| OPS-02 | verify 校验通过 | `scripts/ops.py verify` | exit 0 | pending | 11-ops/ |
| OPS-03 | check 输出 JSON 且退出 0 | `scripts/ops.py check` | exit 0 | pending | 11-ops/ |
| CLN-01 | down -v 后无容器/卷/网络残留 | 三条过滤命令 | 无输出 | pending | 12-cleanup.txt |
| CLN-02 | 证据目录 700/600 | `stat -c %a` | 700 dir / 600 file | pending | 12-cleanup.txt |

## 汇总

| 段 | 总数 | passed | failed | blocked | not-applicable |
|---|---|---|---|---|---|
| A 主机与前置 | 9 | 0 | 0 | 0 | 0 |
| B 冷启动与参考数据 | 11 | 0 | 0 | 0 | 0 |
| C 首管理员 | 5 | 0 | 0 | 0 | 0 |
| D 核心流程 | 7 | 0 | 0 | 0 | 0 |
| E 旧库升级与恢复 | 9 | 0 | 0 | 0 | 0 |
| F Judge0 矩阵 | 9 | 0 | 0 | 0 | 0 |
| G 权限与入口隔离 | 10 | 0 | 0 | 0 | 0 |
| H 持久化与重启 | 6 | 0 | 0 | 0 | 0 |
| I 运维与清理 | 5 | 0 | 0 | 0 | 0 |
| **合计** | **71** | **0** | **0** | **0** | **0** |

> 合计 71 项，当前全部 pending —— 因为本机是 macOS arm64 且 REL-01 发行镜像尚未构建，
> 本批**未执行**任何一项，故不提供任何通过数。
