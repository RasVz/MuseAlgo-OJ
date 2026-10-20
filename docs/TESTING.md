# 核心链路验收

默认单元门禁仍是 `OJ-backend/./mvnw package` 和前端 `npm run lint:ci && npm run build`。
数据库与真实判题验收独立运行，不能用默认门禁代替。

## 独立完整套件

需要 JDK17、Node22.12+、Python3、Docker Compose v2，以及能运行 `linux/amd64` 的 Judge0。
Judge0 镜像约14GB，CI runner 需有足够磁盘；首次下载较慢。Apple Silicon 使用模拟执行，套件自动关闭 Judge0 的 per-process 内存限制；这与原生 Linux 的资源限制验收不同。

```bash
(cd OJ-frontend && npm ci && npx playwright install --with-deps chromium)
python3 tests/run_suite.py --output /tmp/oj-integration
```

套件生成随机项目名、端口、凭据与独立卷；不读取项目 `.env`，不使用现有业务库。结束或异常均停止自己的进程并清理自己的容器、网络和卷。强制杀死运行器、Docker崩溃或主机断电不保证执行清理；按日志中的 `musealgo-ci-*` 项目名核对后再手动清理，禁止全局 prune。

覆盖：

- 真实 MySQL/Redis 的 `-Pintegration`，含权限、任务代次、事务回滚、并发固化、空榜和 VP 延迟判题。
- API 登录→选题→真实 AC/WA/CE；教师建班→邀请码入班→作业发布→学生提交→教师看到完成。
- 对前端构建产物运行两个 Chromium 用例：实际页面登录、题目与已判详情；作业题单显示。
- ACM/OI 整场比赛，暂停Judge0 API服务和异步worker后结束比赛，确认提交仍在途且没有提前固化；恢复后自动固化，再重复重建/固化及VP。仅暂停worker不能延迟`wait=true`同步判题，不能用机器较慢偶然形成的延迟代替故障注入。
- 同一流程分别运行无 MQ、有 MQ、broker 故障三种模式；故障后重启 broker。成绩不依赖 MQ 缓存。

本地只需快速验证主流程时，可加 `--core-only`（保留集成、真实API和浏览器，跳过扩展MQ故障矩阵）；CI默认仍执行完整矩阵。

结果保存在指定输出目录；套件的浏览器失败trace/screenshot与HTML报告写入输出目录的 `browser-results/`、`browser-report/`；单独执行前端测试而未指定输出目录时才使用前端本地 `test-results/` 与 `playwright-report/`。它们只应包含隔离测试数据，不上传真实用户资料。GitHub `integration.yml` 保存7天证据；新工作流须推送后才能在 GitHub 验证，本地成功不等于远端已运行。

## 计分与固化规则

数据库提交流水是权威来源；实时榜按查询时快照计算，SSE 每5秒轮询补送（可配置），MQ 仅加速通知。重启、重复/乱序消息和 Redis 丢失不改变计分。

- 正式榜排除 VP、赛程外提交和未判/基础设施失败的记录。ACM 按提交时间、ID 排序，首 AC 前的失败按现有规则（包括CE）每次加20分钟，后续提交不重复计分；OI 每题取最高分，同分并列。
- 结束后等待在途判题归于结果或 FAILED，数据库事务一次性写成绩、Rating 和完成标记；空榜也有标记。全局结算锁将 Rating 写入串行化，按结束时间与 ID 处理；更早比赛未完成时后续结算等待。
- 同分组选手以平均位置参与 Rating，避免用户ID的排序影响评分。个人当前分、历史和总榜均读数据库。
- VP 交卷或超时后进入 SETTLING，不能再提交；待自己的在途判题和正式榜完成后变为 FINISHED。VP 不修改正式榜和 Rating。
- V54 将已有最终成绩/Rating 的历史比赛标记为已固化，保留旧成绩，不擅自重算历史。升级前应核对历史成绩是否可信；需要纠正时另走受控重判/重算方案。

CI在原生Linux上沿用两项per-process限制为true；Judge0按其[实现](https://github.com/judge0/judge0/blob/v1.13.1/app/jobs/isolate_job.rb)不使用cgroup聚合计量。Apple Silicon的兼容模式不同，不能将其结果等同于所有资源隔离边界均已验收。

本套件不验证所有语言、所有资源异常或生产并发容量。生产恢复演练见 [OPERATIONS.md](OPERATIONS.md)。

## 发行镜像演练

完成镜像构建后可重复执行（仅创建随机命名的独立项目，不连接现有生产栈）：

```bash
python3 tests/operations_drill.py \
  --backend-image musealgo-oj/backend:<本次版本> \
  --frontend-image musealgo-oj/frontend:<本次版本> \
  --previous-backend-image musealgo-oj/backend:<已验证兼容的旧版本> \
  --output /tmp/oj-operations
```

覆盖新装V54、应用DDL拒绝、网关与监控入口、容器重建持久化、备份四份校验、新凭据的新项目恢复、告警正反例、V53→V54重复迁移；只有提供旧镜像时才执行兼容二进制回滚和V53旧备份/旧镜像的结构回滚恢复。不会降级schema或证明任意旧版本均兼容。输出含演练备份，只在可信私有目录保留。

证据上传的版本与参数遵循 [upload-artifact 官方说明](https://github.com/actions/upload-artifact)。
