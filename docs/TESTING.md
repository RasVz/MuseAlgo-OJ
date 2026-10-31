# 核心链路验收

默认单元门禁是 `(cd OJ-Backend && ./mvnw package)` 和前端 `npm run test:unit && npm run lint:ci && npm run build`。
数据库与真实判题验收独立运行，不能用默认门禁代替。

## 独立完整套件

需要 JDK17、Node22.12+、Python3、Docker Compose v2，以及能运行 `linux/amd64` 的 Judge0。
Judge0 镜像约 14GB，CI runner 需有足够磁盘；首次下载较慢。Apple Silicon 使用模拟执行，套件自动关闭 Judge0 的 per-process 内存限制；这与原生 Linux 的资源限制验收不同。

```bash
(cd OJ-Frontend && npm ci && npx playwright install --with-deps chromium)
python3 tests/run_suite.py --output /tmp/oj-integration
```

套件生成随机项目名、端口、凭据与独立卷；不读取项目 `.env`，不使用现有业务库。结束或异常均停止自己的进程并清理自己的容器、网络和卷。强制杀死运行器、Docker 崩溃或主机断电不保证执行清理；按日志中的 `musealgo-ci-*` 项目名核对后再手动清理，禁止全局 prune。

覆盖：

- 真实 MySQL / Redis 的 `-Pintegration`，含权限、任务代次、事务回滚、并发固化、空榜和 VP 延迟判题。
- API 登录 → 选题 → 真实 AC / WA / CE；教师建班 → 邀请码入班 → 作业发布 → 学生提交 → 教师看到完成。
- 对前端构建产物运行 Playwright 用例（`OJ-Frontend/tests/e2e/`）：实际页面登录、题目与已判详情、作业题单、练习分析数字与真实接口对账，以及报告、重判、比赛管理等页面用例。
- ACM / OI 整场比赛，暂停 Judge0 API 服务和异步 worker 后结束比赛，确认提交仍在途且没有提前固化；恢复后自动固化，再重复重建/固化及 VP。仅暂停 worker 不能延迟 `wait=true` 同步判题，不能用机器较慢偶然形成的延迟代替故障注入。
- 同一流程分别运行无 MQ、有 MQ、broker 故障三种模式；故障后重启 broker。成绩不依赖 MQ 缓存。

本地只需快速验证主流程时，可加 `--core-only`（保留集成、真实 API 和浏览器，跳过扩展 MQ 故障矩阵）；CI 默认仍执行完整矩阵。

结果保存在指定输出目录；套件的浏览器失败 trace/screenshot 与 HTML 报告写入输出目录的 `browser-results/`、`browser-report/`；单独执行前端测试而未指定输出目录时才使用前端本地 `test-results/` 与 `playwright-report/`。它们只应包含隔离测试数据，不上传真实用户资料。GitHub `integration.yml` 保存 7 天证据；数据库维护矩阵由独立的 [`database.yml`](../.github/workflows/database.yml) 在 push、pull request 和手动运行时执行并保存 3 天证据。新工作流须推送后才能在 GitHub 验证，本地成功不等于远端已运行。

## 计分与固化规则

数据库提交流水是权威来源；实时榜按查询时快照计算，SSE 每 5 秒轮询补送（可配置），MQ 仅加速通知。重启、重复/乱序消息和 Redis 丢失不改变计分。

- 正式榜排除 VP、赛程外提交和未判/基础设施失败的记录。ACM 按提交时间、ID 排序，首 AC 前的失败按现有规则（包括 CE）每次加 20 分钟，后续提交不重复计分；OI 每题取最高分，同分并列。
- 结束后等待在途判题归于结果或 FAILED，数据库事务一次性写成绩、Rating 和完成标记；空榜也有标记。全局结算锁将 Rating 写入串行化，按结束时间与 ID 处理；更早比赛未完成时后续结算等待。
- 同分组选手以平均位置参与 Rating，避免用户 ID 的排序影响评分。个人当前分、历史和总榜均读数据库。
- VP 交卷或超时后进入 SETTLING，不能再提交；待自己的在途判题和正式榜完成后变为 FINISHED。VP 不修改正式榜和 Rating。
- V54 将已有最终成绩/Rating 的历史比赛标记为已固化，保留旧成绩，不擅自重算历史。升级前应核对历史成绩是否可信；需要纠正时另走受控重判/重算方案。

CI 在原生 Linux 上沿用两项 per-process 限制为 true；Judge0 按其[实现](https://github.com/judge0/judge0/blob/v1.13.1/app/jobs/isolate_job.rb)不使用 cgroup 聚合计量。Apple Silicon 的兼容模式不同，不能将其结果等同于所有资源隔离边界均已验收。

本套件不验证所有语言、所有资源异常或生产并发容量。生产恢复演练见 [OPERATIONS.md](OPERATIONS.md)。

## 业务验收范围

以下测试入口说明每类行为的覆盖边界；是否通过以对应运行的原始证据为准，不以源码或测试文件存在代替。

| 范围 | 自动化入口 | 覆盖与边界 |
|---|---|---|
| 练习分析与账号计数 | `PracticeAnalyticsIT`、`tests/api_smoke.py`、`OJ-Frontend/tests/e2e/` | 只统计 `status<>0` 且 `judge_phase<>'FAILED'`、未删除题目；计入比赛与 VP；30 天窗口为左闭右开；`user.submit_count` / `accepted_count` 由 V55 回填并增量维护，受控重判后须按提交表全表重算 |
| 受控重判 | `RejudgeExecutionIT`、`JudgeExecutionOwnershipIT`、`tests/api_smoke.py` | 单条与范围化批量、已固化/进行中/VP 拒绝、幂等、失败恢复、执行代次隔离、派生数据重算；集成测试临时设置 `OJ_JUDGE_WORKER_ENABLED=false`，真实后端启动前恢复 |
| 比赛资格与双榜 | `ContestRegistrationEligibilityIT`、`ContestGlobalRankIT`、`ContestRankingSseHttpIT` | 等级门槛含下不含上、报名快照、正式榜仅 `OFFICIAL`、全局榜合并 `OFFICIAL/STARRED/VP`、结算只为正式选手写 Rating；管理页面另有 Playwright 用例 |
| 结算与链式重放 | `ContestSettlementIT`、`ContestSettlementReplayIT` | 空榜、并发固化、链范围、确认串、删除赛事、娱乐赛、缺失快照、参与者不一致、过期预览、事务回滚与反向恢复；`OJ_SETTLEMENT_REPLAY_ENABLED` 默认关闭，只在副本演练开启 |
| 报告任务与通知 | `ReportJobIT`、`ReportNotificationIT`、`ReportQueryPlanIT`、`ReportWorkerRecoveryIT`、报告相关 Playwright 用例 | 入队、自然键幂等、权限、租约恢复、失败重试、通知投递与已读、查询计划；契约见 [ARCHITECTURE.md](ARCHITECTURE.md#十报告任务生命周期) |
| 题库访问与 RBAC | `ProblemAccessBoundaryIT`、`ProblemCaseConsistencyIT`、`RbacRevocationIT`、`LastAdminConcurrencyIT` | 私有题/提交访问、用例一致性、撤权即时生效、管理员并发操作 |

## 发行镜像演练

数据库迁移与维护的真实 MySQL 验证由独立测试入口执行，不代替默认单测，也不连接现有 Compose 项目。`tests/flyway_failure_smoke.py` 使用候选发行镜像和仅含 MySQL 的隔离项目，先确认只读维护状态会在未知角色映射时、DDL 前拒绝，再通过打包应用的 Spring/Flyway 启动路径触发 V63 失败，记录存储过程残留、`flyway_schema_history` 的实际状态和无修复重试行为。脚本会在注入故障前导出 V53 数据库快照并校验 SHA-256，在另一新项目恢复该快照、确认合成用户及角色关联保留，再迁移到 V63 并检查成功状态。此脚本覆盖 MySQL 数据恢复，不覆盖 Compose 的三个文件卷；文件卷恢复由下方运维演练验证。

`tests/operations_drill.py` 使用生产 Compose 文件和随机新项目，覆盖新装、只读状态及预览、固定镜像、备份与校验、V53→当前目标升级，以及精确 V62→V63 升级和新项目备份恢复。角色 HTTP 矩阵会在隔离项目创建合成管理员和学生，先验证兼容模式下 GET `/admin/rejudge/operations` 分别返回 200/403，再显式启用规范 RBAC 并反转用户旧 `role` 字段，验证授权仍按 `sys_user_role` 映射返回 200/403；最后恢复原配置并删除合成用户。矩阵不调用管理写接口，证据不保存口令、token 或响应体。演练断言数据库和三个文件卷的状态；自定义 Compose 场景也会比较最终解析的三项镜像 ID。失败时脚本会在私有输出目录保留日志与 JSON 证据，并清理本轮随机项目及卷。不要把真实业务库或凭据用作测试输入。

V62 和旧维护入口镜像由当前候选 JAR 自动生成：V62 fixture 仅移除高于 V62 的迁移，保留维护入口；legacy fixture 仅移除 `DatabaseMaintenance.class`，用于验证状态查询 fail-closed。它们是本轮测试用的合成镜像，不是发行产物。`--previous-backend-image` 是另一项可选参数，仅用于兼容旧程序/旧结构回滚，不承担 V62 或缺少维护入口的 fixture 职责。

本地运行前先按项目构建说明生成当前后端与前端候选镜像，再从后端镜像提取实际打包的 JAR。下面命令使用一次性私有临时目录；若命令中断，先按 Docker project 标签检查并清理本轮随机项目，不要运行全局 prune：

```bash
DB_MATRIX_DIR="$(mktemp -d)"
chmod 700 "$DB_MATRIX_DIR"
BACKEND_IMAGE=musealgo-oj/backend:db-candidate
FRONTEND_IMAGE=musealgo-oj/frontend:db-candidate
V62_IMAGE=musealgo-oj/backend:db-v62-fixture
LEGACY_IMAGE=musealgo-oj/backend:db-legacy-fixture

docker build -f deploy/Dockerfile.backend -t "$BACKEND_IMAGE" .
docker build -f deploy/Dockerfile.frontend -t "$FRONTEND_IMAGE" .
DB_CONTAINER="$(docker create "$BACKEND_IMAGE")"
docker cp "$DB_CONTAINER:/app/app.jar" "$DB_MATRIX_DIR/app.jar"
docker rm "$DB_CONTAINER"

python3 tests/build_database_fixtures.py \
  --source-jar "$DB_MATRIX_DIR/app.jar" \
  --output-dir "$DB_MATRIX_DIR/fixtures" \
  --base-image "$BACKEND_IMAGE" \
  > "$DB_MATRIX_DIR/fixture-manifest.json"
docker build -f "$DB_MATRIX_DIR/fixtures/v62/Dockerfile" \
  -t "$V62_IMAGE" "$DB_MATRIX_DIR/fixtures/v62"
docker build -f "$DB_MATRIX_DIR/fixtures/legacy-maintenance/Dockerfile" \
  -t "$LEGACY_IMAGE" "$DB_MATRIX_DIR/fixtures/legacy-maintenance"

mkdir -m 700 "$DB_MATRIX_DIR/flyway" "$DB_MATRIX_DIR/operations"
python3 tests/flyway_failure_smoke.py \
  --backend-image "$BACKEND_IMAGE" \
  --output "$DB_MATRIX_DIR/flyway"
python3 tests/operations_drill.py \
  --backend-image "$BACKEND_IMAGE" \
  --frontend-image "$FRONTEND_IMAGE" \
  --v62-backend-image "$V62_IMAGE" \
  --legacy-maintenance-image "$LEGACY_IMAGE" \
  --output "$DB_MATRIX_DIR/operations"
```

`flyway-failure-smoke.json` 记录 V63 失败、失败历史与过程残留、重试拒绝，以及已校验 V53 SQL 快照在新项目恢复并升级的结果；`operations-drill.json` 记录新装、角色 HTTP 矩阵、V53/V62 升级、自动四部分备份、恢复和镜像身份核对。两条路径分别覆盖 MySQL 数据恢复与 Compose 数据库/文件卷恢复。真实运行结果只能从对应 JSON 与日志确认，源码或测试存在不等于已通过。证据与备份放私有目录，不提交到仓库。

证据上传的版本与参数遵循 [upload-artifact 官方说明](https://github.com/actions/upload-artifact)。
