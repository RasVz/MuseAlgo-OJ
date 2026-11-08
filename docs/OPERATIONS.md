# 生产账号、备份、恢复和告警

基线为单台 Linux x86_64、Docker Compose、独立 Judge0。以下操作在仓库根目录执行。正式上线仍需提供域名/HTTPS、真实数据和通知接收方；本地演练不等于已经部署线上。

## 三种数据库凭据

`.env` 中分别配置以下随机值，禁止复用，文件权限设为600。`ops.py` 以所选环境文件为准，屏蔽同名宿主Shell变量，避免备份/恢复误连另一套配置；直接使用Compose时仍需注意Shell变量会覆盖环境文件：

| 变量 | 使用者 | 权限与用途 |
|---|---|---|
| `OJ_DB_ROOT_PASS` | MySQL容器与备份恢复命令 | 初始化、账号配置、备份恢复，不注入应用容器 |
| `OJ_DB_MIGRATION_PASS` | MySQL初始化与一次性 `migrate` 服务 | `musealgo_migrator`，仅 `oj_db.*` 的DDL/DML，无全局权限 |
| `OJ_DB_PASS` | 业务后端 | `musealgo_app`，仅 `oj_db.*` 的 SELECT/INSERT/UPDATE/DELETE |

应用和迁移口令使用 `openssl rand -hex 32` 生成，至少32位十六进制。JWT另用 `openssl rand -base64 48`。全新卷自动创建账号；`migrate` 用发行 jar 的独立入口，只执行 Flyway，不启动判题/HTTP/定时任务；成功后业务后端才启动，后端自身 Flyway 关闭。

**已有数据库升级**：初始化脚本不会自动重跑。`OJ_DB_ROOT_PASS` 首先填写数据库当前 root 口令（旧编排中曾是 `OJ_DB_PASS`），另生成新的应用与迁移口令。先设置已验证发行镜像的不可变标签或 digest；不要在维护期间让 `BACKEND_IMAGE` / `FRONTEND_IMAGE` 漂移到另一个构建。维护状态与升级统一通过仓库根目录的 `scripts/ops.py`：

```bash
python3 scripts/ops.py --env-file .env --project musealgo-prod status --json
python3 scripts/ops.py --env-file .env --project musealgo-prod upgrade
# 维护窗口确认预览的版本与镜像引用后，显式执行：
python3 scripts/ops.py --env-file .env --project musealgo-prod upgrade --apply --backup-root /srv/oj-backups
```

`ops.py` 面向 Docker Compose 部署。`status` 只读展示数据库当前版本、程序目标版本、待执行迁移和解析出的不可变镜像身份。`upgrade` 只预览，不停服务、不备份、不修改数据；`upgrade --apply` 会重新预检，并在本次操作中显示并固定所用镜像，然后停写、生成并校验一致备份、运行迁移、检查最终版本并启动服务。工具解析 `migrate`、`backend`、`frontend` 及已配置的 `report-worker` 镜像，要求迁移服务和报告 worker 与后端使用同一镜像；维护期间通过私有临时 Compose override 将这些服务固定为已检查的本地不可变 image ID。因此即使自定义 Compose 文件写死了 tag，也会按该次预检的 ID 执行。旧 Compose 文件没有独立 worker 时仍可使用。仍应确认 Compose 配置指向预期发行版。所有执行使用 `--pull never`。systemd/独立 JAR 使用 `DatabaseMaintenance status` 与显式 `migrate`，不经过 `ops.py`，也不包含自动一致备份；见[部署指南](DEPLOYMENT.md)。

`status --json`、`upgrade` 预览以及成功的 `upgrade --apply` 都在 stdout 输出单个 JSON 对象，适合机器读取。若 apply 发现有待迁移项，它还会把本次预检信息以单行 JSON 输出到 stderr；执行前应核对该镜像 ID 与版本，结束后核对 stdout 中的最终版本、镜像 ID 和已验证备份路径。数据库已匹配目标时，apply 只在 stdout 输出无需升级的 JSON 结果。由于预览和执行是两个独立调用，apply 会以其本次重新预检绑定的身份为准。历史/角色检查失败发生在停写和备份前；备份或校验失败不运行迁移。升级失败会报告阶段、停写情况、备份路径、镜像 ID 和诊断日志，不自动修复历史、不降级也不清卷。维护锁是同一工作区 `outputs/operations` 下按 Compose project 名加的本机 `flock`，只协调同工作区、同主机、相同 project 名的 `ops.py` 维护调用；不会覆盖其他 clone、绕过脚本的写入或跨主机操作。

V63 的角色检查在生产维护入口中会先于迁移执行；开发应用启动使用 Spring Flyway 自动迁移，未知映射可在 V63 创建过程后才由 `CALL` 拒绝。MySQL 的存储过程 DDL 不受过程内 `ROLLBACK` 撤销；若调用失败，后续 `DROP PROCEDURE` 也不会运行，且不能假定 Flyway 失败记录可直接重试。遇到角色映射拒绝、`bootstrap_reference_roles` 已存在或 V63 失败历史时，不要盲目重启后端，也不要运行 `flyway repair`、手动删过程或清空数据卷。先停止自动重试，保留日志和数据库现场；只读检查 `sys_role` 的 ID/code、`information_schema.routines` 中的过程及 `flyway_schema_history` 的 V63 记录，并制作受控私有备份。发行镜像的实际 Flyway 失败状态由[验证矩阵](TESTING.md#发行镜像演练)记录；恢复时在新的 Compose 项目和空卷中按下文校验并恢复匹配的数据库与文件备份，不要覆盖原项目。

修改环境文件不会修改已有 MySQL 账号口令；轮换 root 要通过数据库账号操作单独完成，再同步环境文件。应用容器不加载整份 `.env`，只转发 Compose 中明确列出的配置，新增运行参数需在 Compose 中显式配置。

## 新装后的第一个管理员

空库不带账号，也不应导入演示账号。先在站点注册本人账号并使用独立强口令，再在维护窗口由数据库管理员核对其ID与用户名，授予初始管理员角色：

```bash
docker compose -f docker-compose.prod.yml exec mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot oj_db'
```

在数据库提示符中先用 `SELECT id,username,role FROM user;` 核对，只将已确认属于自己的数字ID对应行更新为 `role=3`（例如 `UPDATE user SET role=3 WHERE id=<已核对的数字ID> AND role=1;`），随后重新登录并从后台管理后续角色。不要把整批注册账号提升为管理员。这里适用于默认兼容角色模式；启用自定义规范RBAC时还须同步明确的角色映射。

**恢复场景**：从备份恢复时管理员账号随数据库一起恢复，账号口令也是备份时的口令；恢复后应立即改口令，并在确认无人依赖旧会话后轮换 `JWT_SECRET` 以作废全部旧 token。若恢复出来的库里没有任何 `role=3` 账号（例如备份来自建站初期），按上面同一流程在维护窗口提升一个已核对账号即可；不要为此另建默认账号，也不要在迁移里插入管理员。

基础角色与权限目录数据初始化，不代表应用已切换到规范RBAC门禁；细权限是否生效仍取决于应用授权模式和接口策略，应按实际启用配置核验。

## 发行镜像、日志与持久化

发行镜像由 `scripts/build-release.sh` 从**干净提交**构建，清单（提交 SHA、镜像 ID、OCI revision、迁移锁摘要、JDK/Node/Nginx 版本）默认写在仓库外的 `outputs/releases/<时间>-<短SHA>/release-manifest.json`。上线时把 `images.backend.id`（push 后改用 RepoDigest 或 `@sha256:...`）写入 `.env` 的 `BACKEND_IMAGE`/`FRONTEND_IMAGE`，再起编排；用 `:latest` 无法证明运行的是哪个提交。清单与构建日志不要提交进仓库。

镜像内的权限与持久化边界（自定义镜像时先回归这几条）：

| 项目 | 期望 |
|---|---|
| 后端进程用户 | 非 root，`musealgo` uid/gid 10001（`docker inspect --format '{{.Config.User}}' <backend>` 应输出 `musealgo`） |
| 程序文件 | `/app/app.jar` 只读（0444，属主 root），容器内不可改写 |
| 可写目录 | `/app/logs`、`/app/OJ-Coursewares`、`/app/OJ-Slidev`（镜像内预建并归 `musealgo`）；`/data/testcases`、`/data/uploads` 由命名卷提供 |
| 后端日志 | logback 所有 profile 写 `logs/oj-<profile>.json.log`（WORKDIR=/app，即容器内 `/app/logs/`），按 100MB×30 天滚动；prod 另输出 JSON 到 stdout，`docker compose logs` 可看 |
| 持久卷 | `mysql-data`、`redis-data`、`testcase-data`、`uploads-data`、`coursewares-data` 五个命名卷；重建容器/升级镜像不丢数据，删卷等于删数据 |
| 前端静态目录 | `coursewares-data`、`uploads-data` 以**只读**挂到 `/var/www/coursewares`、`/var/www/uploads`，由 Nginx worker（uid 101）读取，后端写出的 644 文件可读 |
| 健康探针 | 后端 `/actuator/health`（Nginx 不转发 `/api/actuator/*`，只能从内网或容器内查）；前端 `/healthz` |

全新容器冷启动冒烟（升级前也可用同一套在隔离项目里跑）：`docker compose -f docker-compose.prod.yml up -d` 后应看到 `mysql` healthy → `migrate` 退出码 0 → `backend` healthy → `frontend` healthy；随后用 `curl -fsS localhost:8080/actuator/health`（期望 `{"status":"UP"}`）与 `curl -fsS localhost/healthz`（期望 `ok`）确认，再重启一次容器验证数据卷内容仍在（上传文件、课件、用例、数据库记录）。

## 入口隔离

生产后端端口默认只绑 `127.0.0.1`，MySQL/Redis不发布端口。Nginx不转发 `/api/actuator/*`、`/api/doc.html`、`/api/v3/api-docs*`、`/api/webjars/*`；直接后端的监控指标要求管理员，生产接口文档也要求管理员。健康检查只返回UP/DOWN。生产编排启用prod日志配置，SQL参数（可能包含源码与用户资料）不再逐条输出。运维通过SSH隧道/内网查看，勿将后端绑定改为公网。

课件和上传仍是可按URL访问的静态资源；不要将私密内容当作“知道URL才可看”的安全方案。课件构建只向可信教师/管理员开放，目前未拆成独立执行沙箱。Judge0需要特权容器，应放专用主机/私网并限制来源，不共享业务Docker socket。

## 一致备份与恢复

```bash
# 默认项目musealgo-prod，默认环境文件.env；备份期间短暂停站，随后按原容器配置恢复服务
python3 scripts/ops.py --env-file .env --project musealgo-prod backup --output /srv/oj-backups/2026-10-21-0100
python3 scripts/ops.py --env-file .env --project musealgo-prod verify --source /srv/oj-backups/2026-10-21-0100
```

备份先停止前后端（包括判题写回）及配置中的独立报告 worker，确认它们已停止后，再保存数据库、测试用例、上传、课件四份文件和校验清单。普通备份结束后只恢复原先运行的服务；原先停止的 worker 不会被启动。若发现不在当前配置中的运行中 worker，或无法确认服务已停止，备份会拒绝继续。清单记录来源服务的实际镜像 ID 和备份前运行的服务。输出目录不可已存在，权限700，文件600。失败仍保留残留文件供诊断，没有完整 manifest 的目录不算成功备份。脚本不能停止其他 Compose 项目、独立进程或数据库客户端的写入；运维暂停期间须另行停止这些写入。

备份含源码/用户资料等敏感数据，应加密后复制到独立介质并设置保留周期。`.env`、对象存储数据、外部Judge0/PostgreSQL、TLS私钥与宿主机配置不在此备份中，需分别安全管理；Redis缓存可从业务库恢复，登录黑名单等短期状态不会被此备份还原。

恢复必须使用**没有现有卷的新项目名**，先校验所有哈希与归档成员，拒绝覆盖已有项目/用户/文件。若 MySQL/Redis 启动、目标空库检查、数据库导入、文件卷恢复、迁移或应用健康检查失败，工具会报告停止阶段和已校验来源备份，尝试停止后端、前端及已配置的报告 worker，并检查停写状态；无法确认时会明确报告。目标项目及数据卷保留供诊断，不会自动清理或重置。先核对失败日志和目标状态，再用新的 Compose project 名重试，不要在残留项目上加 `down -v` 后盲目重放。目标环境文件使用新的独立凭据与端口：

```bash
python3 scripts/ops.py --project musealgo-recovery --env-file /secure/recovery.env \
  restore --source /srv/oj-backups/2026-10-20-0100 --confirm-empty-target
```

恢复先初始化目标账号，再恢复 DB 和三个文件卷，运行迁移，然后启动前后端及已配置的报告 worker。校验业务记录数/已知文件哈希/登录与判题后，再由运维切换网关；不要直接把真实站点当作恢复演练目标。

## 升级与回滚

为前后端保留不可变的版本标签或 digest，使用 `BACKEND_IMAGE` / `FRONTEND_IMAGE` 配置。Docker Compose 升级按本节开头的 `status` → `upgrade` 预览 → `upgrade --apply` 操作；有待迁移项时，同一次 apply 会停止前后端和已配置的报告 worker、生成并校验四部分一致备份、运行新版本迁移，再启动这些服务。升级失败时会尝试停写并检查状态；无法确认停写时须立即检查服务。迁移失败时不要重启旧业务继续写未知结构。systemd 单 jar 的独立迁移步骤见[部署指南](DEPLOYMENT.md)。

Flyway 只前进。仅当旧程序兼容新 schema 时才可以切回旧镜像，同时保持迁移镜像为当前版本；否则用旧备份和对应旧镜像在新项目恢复，并显式传 `restore --skip-migrations` 保留备份原结构（后端自身 Flyway 保持关闭），验证后再切换入口。使用 `--skip-migrations` 时，若清单记录了镜像，工具要求当前 backend/frontend 镜像 ID 与记录一致；旧清单没有镜像映射时，须由运维先人工核实镜像与结构配对。如果目标配置中存在 worker，且清单记录它曾运行，还要求它的镜像 ID 匹配并在恢复时启动它；旧清单未记录 worker 运行状态时，恢复不会自动启动 worker。未验证的跨版本回滚不保证可行。每次升级可能新增表或列；“旧程序能启动”不代表新功能和安全修复仍然有效。

## 可执行告警探针

```bash
python3 scripts/ops.py check --backup-root /srv/oj-backups --max-backup-age-hours 26
```

探针输出 JSON，正常退出 0，异常退出 1：检查核心容器，以及已配置的报告 worker 是否运行且通过其现有健康检查；同时检查 10 分钟卡住判题、最近 1 小时 FAILED 任务/事件、结束 15 分钟未结算比赛，以及最近备份的完整性和时效。没有健康检查的服务只检查运行状态。未配置备份目录也报错，避免误以为备份已监控。将其接入现有监控/定时器，按退出码告警；不会擅自向任何人发邮件或消息。没有现成通知渠道时，先保存探针输出到运维日志，再配置明确接收方。

建议每天低峰备份、每5分钟探测、每月在新项目恢复一次。时间点、停站窗口、保留周期与外部通知渠道由实际部署者确定。

Outbox发布最多重试5次，超过预算的FAILED事件需要按消费业务核对后人工处置；不要直接全表重置以免非幂等消费者重复累计。比赛榜单和Rating由数据库重建/结算，不依赖这些事件才能恢复。


## 结算链式重放与反向恢复

此操作会覆盖榜单、评分流水与用户当前分。默认关闭 `OJ_SETTLEMENT_REPLAY_ENABLED`。执行流程：

1. 指定运维负责人、审批人和维护窗口；用 scripts/ops.py 做停写一致备份并校验四部分。维护期间暂停其他成绩/资格/奖项写入。
2. 将备份恢复到隔离项目，先核对 Flyway 版本，再临时设 `OJ_SETTLEMENT_REPLAY_ENABLED=true` 并重建后端。
3. 管理端打开已固化赛事的“结算重放”，检查链范围（包含逻辑删除赛事）、人数、警告、版本和快照，保存预览结果。
4. 填写原因及预览返回的完整 `REPLAY:<比赛ID>:<指纹>` 确认串。状态变化将使预览失效，必须重新核对。
5. 从历史页保存受权备份 JSON，核对当前分、逐场正式/打星号榜、user_rating、settlement_no 与奖项；备份不可公开分享。
6. 在副本先完成“预览恢复→原因与确认串→恢复”演练，逐字段核对 ranks、流水、结算版本与当前分。反向恢复只接受最近成功记录且后续数据完全未变；未知/旧版备份不自动恢复。
7. 审批后方可在目标环境同样执行；结束关闭开关、解除停写并保存审计。没有目标环境真实备份副本的结果，不等于上线验收。

全局结算锁串行化 Rating 事务，比赛行锁阻止编辑/删除竞争；过期确认串阻止第二次重复重放。
失败在单事务内整体回滚，失败尝试由管理接口审计记录；成功记录保留 v2 完整快照与恢复操作者、原因和时间。
数据库恢复是后续数据已变化时的兜底；不要手工改 backup_json 或覆盖后续写入。内置开关不是独立双人审批系统，审批证据需由维护者保留。

## 报告留存、隐私与通知

所有新报告只通过鉴权接口返回 HTML，浏览器打印另存 PDF，不再上传公共 COS。若旧版本曾生成 weekly-reports 对象，
需由存储管理员核对对象 ACL、撤销旧访问并按留存政策处理；本仓库无法证明或撤销既有对象权限。

自动周报**默认关闭**，不会调度；需要时由运维显式设置 `OJ_REPORT_WEEKLY_ENABLED=true` 开启，开启后在每周一 03:00（Asia/Shanghai）预生成上一周期（上周一～上周日）的全量班级报告，执行时间由 `OJ_REPORT_WEEKLY_CRON` 调整。预生成任务只调用与报告中心相同的 enqueue 服务并按 accepted/rejected 计数，不等待 GENERATED，也不直接渲染 HTML；班级较多时可能跨夜排空，处理速度取决于 worker 槽数和单份渲染耗时，不承诺“整班 30 秒完成”。关闭自动调度不影响报告中心的按需受理入口；两者命中同一自然键，已预生成任务直接读取、不重新生成；打开历史任务只发 GET，不要求新的 POST。

| 环境变量 | 默认值 | 含义 |
|---|---|---|
| OJ_REPORT_WEEKLY_ENABLED | false | 预生成开关；默认关闭，需要时显式开启。只 enqueue 任务，不等待 HTML；按需生成报告不受此开关影响 |
| OJ_REPORT_WEEKLY_CRON | 0 0 3 * * MON | 预生成 cron，时区固定 Asia/Shanghai；默认周一 03:00 |
| OJ_REPORT_AI_ADVICE_ENABLED | false | 允许报告调用外部 AI；开启前须完成数据授权和用量记账方案 |
| OJ_REPORT_RETENTION_CLEANUP_ENABLED | true | 在线报告到期清理 |
| OJ_REPORT_RETENTION_MONTHS | 12 | 从任务创建日期算留存月份，最少一个月 |
| OJ_REPORT_RETENTION_CRON | 0 30 3 * * * | 每天 03:30，时区沿用部署 TZ |
| OJ_REPORT_WORKER_ENABLED | true | 报告执行 worker；正常部署只允许一个实例开启，维护期可关闭 |
| OJ_REPORT_WORKER_THREADS | 2 | 每实例执行槽数；claim 每次只领 1 条，不预领超过空槽 |
| OJ_REPORT_WORKER_POLL_INTERVAL_MS | 2000 | 数据库轮询间隔，MQ 关闭时仍推进任务 |
| OJ_REPORT_WORKER_LEASE_TTL_SECONDS | 60 | 生成租约；heartbeat 默认 10 秒 |
| OJ_REPORT_WORKER_MAX_RUNTIME_SECONDS | 90 | 单次运行截止期限，不是完成 SLA |

### 报告 worker 与前端轮询

- 正常部署只选择一个实例设置 `OJ_REPORT_WORKER_ENABLED=true`，其余实例保持关闭但仍可受理任务。judge-only 的 worker profile 默认关闭报告 worker 与通知 poller，避免变成第二个 dispatcher。
- `OJ_REPORT_WORKER_ENABLED=false` 只用于维护：任务仍会 enqueue 并留在 PENDING，worker 恢复后按数据库轮询继续；不存在同步回退。
- 前端只在打开的报告详情轮询当前 job（2 秒），不重叠相同 GET，不为列表行创建定时器；关闭详情、离开页面或标签页隐藏即停止刷新，恢复可见后重新 GET 最新状态。停止刷新不会取消已受理任务。
- 报告详情/HTML 读取收到 403 时前端清空 iframe 与正文并停止轮询。班级批量页按 accepted/status/jobId 解释受理结果，PENDING/GENERATING 是进行态而非失败，并用单个班级列表轮询循环避免 N+1 请求。
- 报告中心可用性只由报告任务 status=GENERATED 决定；提醒是否写入站内通知、用户是否已读分别由通知域记录。提醒投递失败不会把报告任务改成 FAILED，也不应显示为生成失败。

每批最多清理 500 个超过留存期的终态（GENERATED/FAILED）任务及其 delivery/attempt 记录；只要任一关联通知意图仍为非终态或存在未来重试，该 job 整组保留，PENDING/GENERATING 与活动租约不清理。
大积压需要多次调度，不在单个事务里全量清空；日志记录截止日期与删除数量。在线删除不自动清理 SQL 备份、已下载文件或旧 COS 对象；这些副本需单独制定访问与到期销毁政策。
备份四部分中的 SQL 已包含 report_job HTML 与姓名快照，不需要在公开上传目录新增报告副本。
站内通知仅保存报告链接意图，不保存正文；报告过期后旧通知打开会显示任务不存在。

EMAIL/SMS/WECHAT 保持 DRY_RUN，任何配置都不会启用真实发送。邮箱验证、家长关系与 consent、退订、收件人频控、密钥管理、独立发送审计完成并获得明确授权前不得接入真实通道。
REPORT_READY 遵守用户“学习报告通知”开关；站内交付状态表示报告已在中心可用，不代表收件人已阅读。


新装结构基线不含演示账号或业务数据；MySQL 初始化会通过 `01-reference-roles.sql` 建立基础角色与默认授权，V63为既有库补目录和缺失角色，只给新创建的ADMIN/TEACHER授默认权限；已存在角色的刻意撤权保留，旧演示映射仅在完全匹配已知模式时转换。不要向生产导入演示账号。
