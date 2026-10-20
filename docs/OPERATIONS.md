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

**已有数据库升级**：初始化脚本不会自动重跑。`OJ_DB_ROOT_PASS` 首先填写数据库当前 root 口令（旧编排中曾是 `OJ_DB_PASS`），另生成新的应用与迁移口令；暂停业务并备份，再更新 MySQL 容器环境并执行：

```bash
docker compose -f docker-compose.prod.yml stop frontend backend
docker compose -f docker-compose.prod.yml up -d --wait mysql redis
docker compose -f docker-compose.prod.yml exec -T mysql bash /docker-entrypoint-initdb.d/10-users.sh
docker compose -f docker-compose.prod.yml run --rm migrate
docker compose -f docker-compose.prod.yml up -d --wait backend frontend
```

修改环境文件不会修改已有 MySQL 账号口令；轮换 root 要通过数据库账号操作单独完成，再同步环境文件。应用容器不加载整份 `.env`，只转发 Compose 中明确列出的配置，新增运行参数需在 Compose 中显式配置。

## 新装后的第一个管理员

空库不带账号，也不应导入演示账号。先在站点注册本人账号并使用独立强口令，再在维护窗口由数据库管理员核对其ID与用户名，授予初始管理员角色：

```bash
docker compose -f docker-compose.prod.yml exec mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot oj_db'
```

在数据库提示符中先用 `SELECT id,username,role FROM user;` 核对，只将已确认属于自己的数字ID对应行更新为 `role=3`（例如 `UPDATE user SET role=3 WHERE id=<已核对的数字ID> AND role=1;`），随后重新登录并从后台管理后续角色。不要把整批注册账号提升为管理员。这里适用于默认兼容角色模式；启用自定义规范RBAC时还须同步明确的角色映射。

## 入口隔离

生产后端端口默认只绑 `127.0.0.1`，MySQL/Redis不发布端口。Nginx不转发 `/api/actuator/*`、`/api/doc.html`、`/api/v3/api-docs*`、`/api/webjars/*`；直接后端的监控指标要求管理员，生产接口文档也要求管理员。健康检查只返回UP/DOWN。生产编排启用prod日志配置，SQL参数（可能包含源码与用户资料）不再逐条输出。运维通过SSH隧道/内网查看，勿将后端绑定改为公网。

课件和上传仍是可按URL访问的静态资源；不要将私密内容当作“知道URL才可看”的安全方案。课件构建只向可信教师/管理员开放，目前未拆成独立执行沙箱。Judge0需要特权容器，应放专用主机/私网并限制来源，不共享业务Docker socket。

## 一致备份与恢复

```bash
# 默认项目musealgo-prod，默认环境文件.env；短暂停站，按原容器配置恢复原本运行的服务（不会顺带升级）
python3 scripts/ops.py backup --output /srv/oj-backups/2026-10-20-0100
python3 scripts/ops.py verify --source /srv/oj-backups/2026-10-20-0100
```

备份先停止前后端（包括判题写回），再保存数据库、测试用例、上传、课件四份文件和校验清单。输出目录不可已存在，权限700，文件600。失败仍保留残留文件供诊断，没有完整manifest的目录不算成功备份。运维暂停期间也不要从其他数据库客户端写业务数据。

备份含源码/用户资料等敏感数据，应加密后复制到独立介质并设置保留周期。`.env`、对象存储数据、外部Judge0/PostgreSQL、TLS私钥与宿主机配置不在此备份中，需分别安全管理；Redis缓存可从业务库恢复，登录黑名单等短期状态不会被此备份还原。

恢复必须使用**没有现有卷的新项目名**，先校验所有哈希与归档成员，拒绝覆盖已有项目/用户/文件。目标环境文件使用新的独立凭据与端口：

```bash
python3 scripts/ops.py --project musealgo-recovery --env-file /secure/recovery.env \
  restore --source /srv/oj-backups/2026-10-20-0100 --confirm-empty-target
```

恢复先初始化目标账号，再恢复DB和三个文件卷，运行迁移，启动服务。校验业务记录数/已知文件哈希/登录与判题后，再由运维切换网关；不要直接把真实站点当作恢复演练目标。

## 升级与回滚

为前后端保留不可变的版本标签或digest，使用 `BACKEND_IMAGE` / `FRONTEND_IMAGE` 配置。升级前做上述四份一致备份；先停业务，再运行新版本 `migrate`，然后启动后端和前端。迁移失败时不要重启旧业务继续写未知结构。

Flyway只前进。仅当旧程序兼容新schema时才可以切回旧镜像，同时保持迁移镜像为当前版本；否则用旧备份和对应旧镜像在新项目恢复，并显式传 `restore --skip-migrations` 保留备份原结构（后端自身Flyway保持关闭），验证后再切换入口。未验证的跨版本回滚不保证可行。V54是增量列/表，但“旧程序能启动”不代表新功能、安全修复仍然有效。

## 可执行告警探针

```bash
python3 scripts/ops.py check --backup-root /srv/oj-backups --max-backup-age-hours 26
```

探针输出JSON，正常退出0，异常退出1：检查核心容器健康、10分钟卡住判题、最近1小时FAILED任务/事件、结束15分钟未结算比赛，以及最近备份的完整性和时效。未配置备份目录也报错，避免误以为备份已监控。将其接入现有监控/定时器，按退出码告警；不会擅自向任何人发邮件或消息。没有现成通知渠道时，先保存探针输出到运维日志，再配置明确接收方。

建议每天低峰备份、每5分钟探测、每月在新项目恢复一次。时间点、停站窗口、保留周期与外部通知渠道由实际部署者确定。

Outbox发布最多重试5次，超过预算的FAILED事件需要按消费业务核对后人工处置；不要直接全表重置以免非幂等消费者重复累计。比赛榜单和Rating由数据库重建/结算，不依赖这些事件才能恢复。
