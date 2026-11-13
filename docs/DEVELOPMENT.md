# 开发指南

> 个人开发入口：环境、启动、数据库迁移、构建测试与代码约定。文档索引见 [docs/README.md](README.md)。

## 一、环境要求

| 工具 | 版本 | 说明 |
|---|---|---|
| JDK | **17**（release 17） | 21 / 24 可运行；项目以 17 为发布目标 |
| Maven | 3.9+ | 仓库自带 `mvnw` 包装器，无需单独安装 |
| Node.js | `^20.19.0 \|\| >=22.12.0` | 前端要求（见 `OJ-Frontend/package.json` 的 `engines`） |
| Docker Desktop | 最新 | 推荐路线，一键起全部依赖 |

**推荐资源上限**：Docker Desktop 至少 **4 CPU / 4 GB**。核心四容器（MySQL + Redis + 后端 + 前端）加 Maven 编译在 2 GB 下会明显卡顿，判题机另需约 1 GB。

---

## 二、启动

### 路线 A：Docker（推荐）

```bash
cp .env.example .env
# 填 JWT_SECRET —— 生成方式：openssl rand -base64 48

docker compose -f compose.dev.yml up -d
```

首次启动 MySQL 会依次执行 `docker/mysql-init/00-schema.sql`（V53 表结构基线）和 `01-reference-roles.sql`（基础角色与权限目录），后端随后通过 Flyway 校验并应用增量迁移。可用以下地址：

- 前端 http://localhost:5173 ｜ 后端 http://localhost:8080
- **接口文档 http://localhost:8080/doc.html**
- 健康检查 http://localhost:8080/actuator/health

**导入演示数据**（可选）：`./scripts/db-seed.sh`，之后可用 `admin / 123456` 登录。

**需要判题时**：`docker compose -f compose.dev.yml --profile judge up -d`（镜像约 14 GB，首次拉取较久）。

> 后端源码整仓挂载，改完 Java 代码执行 `docker compose -f compose.dev.yml restart backend` 即可；前端有 HMR，改动即时生效。

### 路线 B：本地原生

需要自备 JDK 17、MySQL 8、Redis 与 Node.js（建议 22.12+）。Maven 使用仓库包装器即可。
本路线默认连接本机 MySQL **3306**、Redis **6379**；Docker 开发栈映射的 3307 / 6381 不能直接代用。

1. 在全新数据库上按 [部署指南的数据库初始化步骤](DEPLOYMENT.md#31-初始化原生数据库) 创建 `oj_db`、专用账号，依次导入 `00-schema.sql` 与 `01-reference-roles.sql`。已有库不要重复导入基线或重放初始化脚本。
2. 从仓库根目录执行 `cp .env.example .env`，填写 `JWT_SECRET`、`OJ_DB_USER`、`OJ_DB_PASS`；无可选中间件时增加：

   ```dotenv
   OJ_RABBITMQ_ENABLED=false
   OJ_SEARCH_ENABLED=false
   MANAGEMENT_HEALTH_RABBIT_ENABLED=false
   OJ_PUBLIC_BASE_URL=
   ```

   密钥可用 `openssl rand -base64 48` 生成；`.env` 被脚本作为 shell 文件加载，含空格或特殊字符的值须正确加引号。

3. 两个终端都从仓库根目录执行：

```bash
# 终端一：脚本加载 .env，先安装所有依赖模块，再启动 oj-admin
./scripts/run-local.sh
```

```bash
# 终端二：前端（/api 与 /uploads 默认代理到 localhost:8080）
(cd OJ-Frontend && npm ci && npm run dev)
```

若需手动启动，以下是 `run-local.sh` 的等价步骤（同样从仓库根目录执行）：

```bash
(
  set -e
  set -a
  . ./.env
  set +a
  cd OJ-Backend
  ./mvnw -pl oj-admin -am -DskipTests install &&
    ./mvnw -pl oj-admin spring-boot:run
)
```

安装阶段使用 `-am`；启动阶段不加 `-am`，以免对不可执行的父工程执行 `spring-boot:run`。
如需在线生成课件，还应执行 `(cd OJ-Slidev && npm ci --omit=dev)`。

#### 本机课堂配置（local-classroom）

课堂演示可用显式 profile `local-classroom` 启动，非课堂环境无需启用：

```bash
SPRING_PROFILES_ACTIVE=local-classroom ./scripts/run-local.sh
# 或给 jar 追加：--spring.profiles.active=local-classroom
```

该 profile 在 `application.yml` 基础上只覆盖以下值，均可用环境变量改回：

- 登录限流：`ip-max-attempts=120`、`ip-window-seconds=60`、`account-max-failures=5`、`account-window-seconds=60`、`account-cooldown-seconds=60`，分别由 `OJ_SECURITY_LOGIN_IP_MAX_ATTEMPTS`、`OJ_SECURITY_LOGIN_IP_WINDOW_SECONDS`、`OJ_SECURITY_LOGIN_ACCOUNT_MAX_FAILURES`、`OJ_SECURITY_LOGIN_ACCOUNT_WINDOW_SECONDS`、`OJ_SECURITY_LOGIN_ACCOUNT_COOLDOWN_SECONDS` 覆盖。
- 数据库等待：Hikari `connection-timeout=2000ms`（`OJ_DB_CONNECTION_TIMEOUT_MS`）、JDBC `connectTimeout=2000ms`（`OJ_DB_CONNECT_TIMEOUT_MS`）、`socketTimeout=5000ms`（`OJ_DB_SOCKET_TIMEOUT_MS`）。
- `server.forward-headers-strategy=none`。

前置的 MySQL、Redis 与密钥仍按上文既有说明配置；profile 只调整应用参数，不会自动启动这些服务。

注意：置于反向代理后时所有用户共享代理对端额度，当前版本忽略客户端自报的 IP 头，未来部署需要可信代理边界。连接池 2000ms 仅限制取连接等待，不是整个 HTTP 请求或 SQL 执行时限，不承诺 20 人长期稳定。

### 2.1 管理中心 AI 服务（可选）

默认仍使用 `.env` 里的 `DEEPSEEK_*` / `AI_MODEL`（`LEGACY`）。要验证后台服务切换，需要一份独立主密钥和只读挂载：

```bash
mkdir -p deploy/dev-secrets
openssl rand -base64 32 > deploy/dev-secrets/oj_ai_master_key
chmod 600 deploy/dev-secrets/oj_ai_master_key
OJ_AI_MASTER_KEY_FILE_HOST="$PWD/deploy/dev-secrets/oj_ai_master_key" \
  docker compose -f compose.dev.yml -f deploy/compose.dev.ai-service-secret.yml up -d backend
```

启动后进入「管理中心 → AI 与运维 → AI 服务」添加服务；保存只代表可测试，点「设为当前」才会切到 `MANAGED`。生产挂载、主密钥备份/轮换与 SSRF 边界见 [AI-SERVICES.md](AI-SERVICES.md)。

`deploy/dev-secrets/` 已被 `.gitignore` 排除，不要把真实 Key 或主密钥写入 `.env.example` 或提交到仓库。

---

## 三、数据库迁移（必读）

这是本项目最容易踩坑的地方，请务必读完。

### 3.1 迁移文件的位置与命名

迁移位于 `OJ-Backend/oj-admin/src/main/resources/db/migration/`，命名为 `V{版本号}__说明.sql`，并保持在该目录平铺存放。检查脚本从迁移文件中发现版本，不要在文档或脚本中预先写死下一个编号。运行 `python3 scripts/check_migrations.py` 可检查命名、连续性、目录布局和 SHA-256 登记；加 `--trusted-base <git-ref>` 可再与可信 Git 历史比较。CI 会自动传入 PR 目标提交或 push 前提交。

### 3.2 ⚠️ 已发布的迁移文件不要修改

Flyway 的 checksum 会校验迁移文本，**修改注释或其他行内容也可能让已有数据库在启动时报校验失败**。需要修正时，请新增一个使用下一个连续版本号的迁移，不要改动已有文件。

**已执行或已分发给协作者的迁移一律冻结**，不以仓库是否公开为判断依据。任何新增迁移必须使用下一个连续版本；不要删除、合并或改写既有迁移。
基线维护与历史校验说明见 [数据库初始化目录](../docker/mysql-init/README.md#迁移维护规则)。

### 3.3 基线与两种迁移入口

`application.yml` 里配置了：

```yaml
flyway:
  baseline-on-migrate: true
  baseline-version: 6
```

这是开发应用启动时使用的配置。按当前受支持的初始化路径，V1–V6 是历史参考文件，基线已含其后续结构与历史记录；开发启动只应用基线尚未记录的增量。不能把 V1–V6 当作空库建表脚本。V1 第一条外键引用了 `` `user` `` 表，而该表不由迁移文件创建。因此：

> **空库直接跑 Flyway 必然失败**（`Failed to open the referenced table 'user'`）。

仓库用 `docker/mysql-init/00-schema.sql` 承担结构基线角色：这是 V53 时点的快照，包含 59 张表结构和 48 条 Flyway 历史记录，不含业务数据。首次初始化时，MySQL 随后执行 `01-reference-roles.sql`，写入三个基础角色、四个 V1 基础权限码，并授予 ADMIN 四项、TEACHER 前三项权限；不创建学生权限或用户账号。该文件先于后续迁移运行，确保新装库在增加功能权限关联前已有角色目录；V56/V60 之后增加的功能权限仍由迁移按权限码处理。演示账号和题目由 `scripts/db-seed.sh` 单独导入。V54–V63 的迁移静态新增 8 张表，到 V63 时的推导值为 67 张表；后续迁移及实例当前版本以迁移文件和维护状态查询为准。

V63 为既有库补齐基础角色和权限目录；只为该迁移中新建的 ADMIN/TEACHER 授默认权限，不会因为基础目录缺项或其他角色缺失而回填既有角色已撤销的授权。已知旧演示角色反向映射会保持用户的实际角色含义，并把特定管理员权限从旧 STUDENT 映射转到纠正后的 ADMIN；只有完全匹配已知模式时才转换，未知或冲突映射会停止。新装基础角色由 `01-reference-roles.sql` 创建，演示账号仍由 `scripts/db-seed.sh` 单独导入。

生产 Docker Compose 在独立的一次性迁移容器中运行 Flyway，业务账号没有 DDL 权限。独立入口使用 `baseline-on-migrate=false`，要求目标库已有受支持的结构基线与 Flyway 历史；它不会替空库生成基线。开发应用使用 `baseline-on-migrate=true` / `baseline-version=6`，启动时校验并应用基线之后的增量；在本项目支持的路径中，V1–V6 是历史参考文件，不是空库建表脚本。MySQL 存储过程的创建属于不随过程内 `ROLLBACK` 撤销的 DDL；若 `CALL` 失败，过程可能保留，后续清理语句也不会运行。此时不能假定 Flyway 失败记录或可重试状态，应先停止自动重试并按[故障排查](TROUBLESHOOTING.md#v63角色初始化失败或留下存储过程)只读检查过程、迁移历史和角色映射。发行镜像真实数据库验证的两条入口与覆盖范围见[测试指南](TESTING.md#发行镜像演练)；具体结果以对应版本的测试证据为准。Compose 的状态、预览、升级、备份和恢复见[运维指南](OPERATIONS.md)；systemd 独立 JAR 入口见[部署指南](DEPLOYMENT.md)第三节。

### 3.4 新增迁移的正确姿势

```bash
# 1. 在 db/migration/ 下新增符合命名规则的迁移文件
# 2. 审查SQL后，以尚未包含新迁移的可信Git提交登记新增版本（只追加清单）
python3 scripts/check_migrations.py --trusted-base HEAD --record-new
# 3. 本地检查命名、连续性、目录布局与文件摘要
python3 scripts/check_migrations.py
# 4. 本地开发栈重启后端后会应用尚未记录的迁移
docker compose -f compose.dev.yml restart backend
docker compose -f compose.dev.yml logs -f backend | grep -i flyway
```

新增迁移后，现有结构基线通常无需重导：新安装会先导入基线，再运行尚未记录的新版本。首次登记时，`HEAD` 必须指向新增迁移尚未进入的可信提交；若当前分支已包含新增文件，应使用 PR base 或 push 前提交。普通本地检查不要求 `--trusted-base`；登记时 `--record-new` 必须同时指定可信基线，且只追加从未登记的文件。CI 再以可信 Git 对象独立校验迁移文件与清单；PR 使用目标提交，push 使用 push 前提交（新分支使用远端默认分支）。SHA-256 清单用于发现内容变化，不是独立签名；历史迁移冻结仍以可信 Git 历史为准。只有明确更新新装快照时，才从隔离且校验通过的同一数据库导出结构与迁移历史；规则见[初始化目录说明](../docker/mysql-init/README.md#迁移维护规则)。业务备份不得放入 MySQL 初始化目录。

---

### 3.5 演示判题用例的存放与安装

`problem_case` 表里存的是**相对路径**（`<problemId>/<caseId>/input.txt`），真实内容在文件系统上，
根目录由 `oj.testcase.base-path` 决定（开发栈为 `OJ-Backend/data/testcases`）。

演示用例的源文件在 `OJ-Backend/db/demo-testcases/`，其期望输出由
`scripts/gen-demo-testcases.py` 里的参考实现计算得出，可用 `--check` 复核是否与文件一致。
`./scripts/db-seed.sh` 会先把用例复制到用例目录，再导入 SQL。

> **不要只执行 `seed.sql`**：那样库里只有路径、盘上没有文件，判题会因读不到用例而失败。

## 四、工程结构

```
OJ-Backend/                 12 个 Maven 模块（详见 docs/ARCHITECTURE.md）
├── oj-admin/               唯一启动模块 + Flyway 迁移 + 跨域 Controller
├── oj-api/                 跨模块契约（判题网关、AI 接口、只读查询 DTO）
├── oj-<业务域>/             各业务模块（system / problem / judge / contest / ...）
├── db/                     seed.sql（演示数据）与 demo-testcases/（演示用例）
└── pom.xml                 父工程

OJ-Frontend/                Vue 3 + Vite 单页应用
├── src/views/              页面，按业务域分目录
├── src/api/                接口封装
├── src/router/             路由与权限守卫
├── src/stores/             Pinia 状态
└── vite.config.js          开发期 /api 反向代理（目标由 VITE_PROXY_TARGET 决定）

OJ-Slidev/                  Slidev 课件工程
docker/                     Maven 镜像配置、Judge0 配置、数据库初始化目录
scripts/                    开发脚本
compose.dev.yml             开发栈编排（核心四件套 + judge / extras 两个 profile）
```

---

## 五、常见开发任务

### 5.1 新增一个后端接口

1. 在对应业务模块的 `controller` 包加 Controller 方法，只做「收 DTO → 调应用服务 → 返回结果」。
2. 写操作必须落在 **`com.musealgo.oj.<module>.application` 应用服务**里，由它负责权限校验、事务边界、字段白名单复制。
3. **不要**在 Controller 里直接调 Mapper，也**不要**跨模块访问别人的 Mapper——跨域读取走 `oj-api` 里的只读查询接口。
4. ArchUnit 当前自动校验跨业务模块的实现层依赖，具体范围见 [架构说明](ARCHITECTURE.md#三依赖约束重要)。Controller 写操作分层等约定仍需代码评审检查。

### 5.2 新增一个前端页面

1. 在 `src/views/<域>/` 下建页面组件。
2. 在 `src/router/index.js` 注册路由，按需加权限守卫。
3. 接口调用统一放 `src/api/`，注意路径以 `/api` 为前缀（开发期由 Vite 代理）。
4. 提交前跑 `npm run lint` 与 `npm run format`。

### 5.3 新增一个配置项

1. `application.yml` 用 `${VAR:默认值}` 占位。
2. 同步更新 `.env.example`（含注释说明）。
3. 若是必填项，**不要给默认值**——让它缺失即失败，比带着弱默认值上生产安全。
4. 更新 README 的配置说明表格。
5. 通常可选配置在 `.env.example` 里应**整行注释掉**，避免无意写成 `KEY=`。
   明确需要空字符串时例外，例如原生同源部署使用 `OJ_PUBLIC_BASE_URL=`。
   留空赋值会覆盖 `application.yml` 里的 `${KEY:默认值}`，把默认值抹成空字符串
   （`AI_MODEL`、`DEEPSEEK_API_URL` 都踩过这个坑）。

### 5.4 修改种子数据

`OJ-Backend/db/seed.sql` 是**演示数据**，导入后立即可用 `admin / 123456` 登录。修改时请注意：

- 表结构以 Flyway 迁移为最终准绳。历史上这里踩过坑：`V52` 已把 `submission.code` 拆到 `submission_code` 表，而 `seed.sql` 仍按旧结构插入，导致导入中断、后续种子数据全部丢失。
- 用户口令是 **bcrypt 哈希**，必须与文件头标注的明文口令（`123456`）真实匹配，否则「导入了却登不进去」。

### 5.4.1 给在用的开发库补测试数据

`seed.sql` 面向**全新数据库**，重复执行会因主键冲突中断；已经在用的开发库请改用
`./scripts/db-seed-testenv.sh`（`--dry-run` 只生成 SQL）：

- 幂等：全部写入使用固定 id 段（用户 `1001+`、提交 `10001+`、比赛/试卷 `1001+` 等）配合
  `ON DUPLICATE KEY UPDATE` / `INSERT IGNORE`，重复执行不产生重复行；比赛与提交时间按执行时刻重算。
- 只补不改：新增 50 名学生与 1200 条跨三个月的提交、4 场不同状态的比赛、班级作业与课件、
  社区标签与错题本等；对既有数据只做两类修正 —— 恢复 9–15 号题被删除的判题用例记录，
  以及按源表重算冗余计数（`submit_count` / `pass_count` / `student_count` / `problem_count`）。
- 覆盖到的功能面：题库、题单、班级作业与学情、比赛（报名审核/进行中/已结束/班级赛）、
  **比赛等级配置**（4 个启用等级 + 1 个停用示例，比赛行快照 `rating_min/max`）、
  **训练试卷**（8 份在库、5 个分类，含客观题卷、纯编程卷与混合卷，以及 RUNNING/SUBMITTED/TIMEOUT
  三种作答会话）、**结算与结算回放**、**重判任务与明细**、**查重报告**、**虚拟赛会话**、内容举报。
- 会公开 177 道标题整洁且带判题用例的 FPS 题（题库 27 → 204），并给这批题分配**合成的**
  难度档位以便验证筛选与排序；两者的回滚语句写在生成的 SQL 注释里。
- 42 号题的用例由脚本内的参考实现生成，执行时一并写入 `OJ-Backend/data/testcases/42/`。
- 容器的 `NOW()` 为 UTC，脚本写入的时间为宿主本地时间；两者相差 8 小时属正常现象，
  不要据此判断数据错位。

> 脚本会直接写库、绕过业务逻辑，因此末尾统一对账冗余计数；执行前请先备份目标库。

### 5.5 提交与检查

- 提交信息使用 Conventional Commits 前缀：`feat` / `fix` / `refactor` / `perf` / `docs` / `test` / `chore`，一个提交只做一件事。
- 默认提交门禁是后端 `./mvnw test` 与前端 `npm run lint:ci && npm run build`；本机 MySQL / Redis 就绪时可加跑 `-Pintegration`，命令见第六节。
- 仓库配有 pre-commit 密钥扫描（`scripts/secret-check-pre-commit.sh`）与 CI 的 gitleaks 工作流。不要提交密钥、口令、Token、数据库导出、`node_modules`、`target` 或 `dist`。
- 新增配置项时同步更新 `.env.example` 与 `application.yml`；必填项不要给默认值，可选配置在 `.env.example` 中整行注释掉（例外见 5.3）。

### 5.6 代码风格与注释

- 后端遵循 Spring / MyBatis-Plus 常规实践与 Java 17 语法；前端使用 ESLint + Prettier。注释与文档以中文为主，技术名词保留英文。
- 注释判断标准：**删掉这行注释，下一个人会不会做出错误的改动？** 会则写，不会则不写。类/接口说明职责、边界与存在理由；非平凡公开方法写清做什么、参数约束、返回语义和异常；纯 getter/setter、纯 CRUD Controller 方法、Vue 模板区不写。
- 最有价值的是**决策型注释**：重构、绕过框架限制、性能取舍处，说明为什么不能采用看起来更简单的写法。注释或格式整理单独提交，不与功能改动混在一起。
- 以下已知坑在改动相关代码时保留对应注释：
  - Mapper XML 注释内不能出现连续两个以上 `-`，分隔线用 `====`，否则 MyBatis 解析失败；
  - 带 `<script>` 的 `@Select` 需要 XML 转义，不带的走原始 SQL，反而不能转义；
  - 同一 mapper id 不能同时存在注解与 XML 定义；
  - `problem_tag` / `user_tag_rating` 是联合主键，故意不加 `@TableId`；
  - 已执行或已分发的 Flyway 迁移（含注释）冻结，修正新增 `V{n+1}`；
  - `SlidevPathProvider.findWorkspaceRoot()` 找不到工作区根时兜底 `user.dir`；`courseware.url` 中的 uuid 目录名不可改；
  - `logback-spring.xml` 有意把两类 MyBatis-Plus 良性 WARN 降到 ERROR。
- Mapper XML 三条硬性要求：注释不得含 `--`；SQL 比较运算符按 XML 规则转义；不要为格式重排 SQL。时间窗口的 `from/to` 在本仓库一律半开 `[from, to)`，`startTime/endTime` 则按具体查询有闭区间和半开两种口径，改动前通读完整 WHERE 条件。
- 新增 SQL 迁移的文件头写清**业务问题、处理方式和前置依赖**，三要素缺一不可。

---

## 六、构建与测试

以下命令均从仓库根目录执行；前端先执行 `(cd OJ-Frontend && npm ci)` 安装依赖。

```bash
# 后端：编译（快）
(cd OJ-Backend && ./mvnw -q compile)

# 后端：跑单元测试
(cd OJ-Backend && ./mvnw test)

# 后端：打包（会先跑一遍单元测试）
(cd OJ-Backend && ./mvnw package)
# 产物：OJ-Backend/oj-admin/target/oj-admin-0.1.0-SNAPSHOT.jar

# 后端：连需要数据库的集成测试一起跑（需本机 MySQL / Redis 已就绪）
# 该 profile 会清空默认排除项，并显式 include **/*IT.java，
# 因此单元测试与集成测试都会被执行
(cd OJ-Backend && ./mvnw test -Pintegration)

# 前端：代码检查（lint:ci 不带 --fix，与 CI 一致）
(cd OJ-Frontend && npm run lint:ci)

# 前端：本地开发时可用带自动修复的版本
(cd OJ-Frontend && npm run lint)

# 前端：构建
(cd OJ-Frontend && npm run build)
```

**测试分两层**：

| 层 | 内容 | 运行条件 |
|---|---|---|
| 单元测试 | 各模块的领域逻辑与 Service 测试，含 ArchUnit 模块依赖检查 | 无外部依赖，**CI 默认执行** |
| 集成测试 | `-Pintegration` 下的 `*IT`：容器启动、权限、并发、报告、比赛与结算等 | 需要 MySQL / Redis，已由 `pom.xml` 默认排除；`-Pintegration` 会清空排除项并显式 include `**/*IT.java`（默认命名规则不含 `*IT`，只清 excludes 是选不中的） |

> 默认排除集成测试而不是让 `mvn test` 直接失败，是有意的：否则所有人（包括 CI）都会用
> `-DskipTests` 把测试整体跳过，判题、榜单、查重等核心逻辑被改坏也不会被发现。
>
> 位于 `oj-admin` 的 `ModuleDependencyArchTest`（ArchUnit）负责校验模块依赖规则，
> 属于单元测试层，改动模块依赖关系后 CI 会自动拦下。

社区、课件和真实搜索的串行功能回归需单独准备一次性测试栈，并载入
`OJ-Backend/db/seed.sql` 的合成账号。设置 `OJ_TEST_API`、`OJ_TEST_WEB` 和
`OJ_TEST_OUTPUT_DIR` 后运行；前端代理指向该测试后端。课件阶段需要已安装依赖的
Slidev 工厂，后端配置 `OJ_SLIDEV_WORKER_DIR` / `OJ_SLIDEV_OUTPUT_DIR`。
搜索阶段启用真实 Meilisearch，测试端设置 `OJ_TEST_MEILI`、`OJ_TEST_MEILI_KEY`，
后端使用对应的 `OJ_SEARCH_MEILISEARCH_URL` / `OJ_SEARCH_MEILISEARCH_API_KEY`。

```bash
python3 tests/modules_smoke.py community --label community-final
python3 tests/modules_smoke.py courseware --label courseware-final
python3 tests/search_smoke.py normal --label search-after
python3 tests/search_consistency_smoke.py
# 停止测试搜索服务后执行；脚本会确认服务不可达。
python3 tests/search_smoke.py outage --label search-outage
# 重新启动同一个测试搜索服务，再重建索引验证恢复。
python3 tests/search_smoke.py recovery --label search-recovery
(cd OJ-Frontend && OJ_MODULE_SMOKE_ENABLED=1 npx playwright test modules-functional.spec.js)
```

这些脚本会创建、编辑和删除合成业务数据，搜索用例还会临时修改索引，
仅用于一次性测试环境。浏览器专项默认不加入普通套件；显式启用时需要上述同名 JSON
夹具，缺失会失败。运行后回收本轮服务与卷。全文搜索最多校验1000个候选ID，按数据库
公开/未删除及难度条件筛选后统一计数和分页，保留索引相关性顺序；达到服务端分页上限、
超过候选预算或候选不完整时回退SQL。后端密钥须能读取索引分页设置，否则也回退SQL。
数据库读取使用同一只读快照，SQL回退同样排除软删除行。索引漏收的新题仍需同步或重建，
本机制不扩大Meilisearch自身的检索窗口，也不承诺索引内容实时一致。

常用的排错手段：

```bash
docker compose -f compose.dev.yml logs -f backend        # 跟踪后端日志
docker compose -f compose.dev.yml restart backend        # 改 Java 代码后重启
docker compose -f compose.dev.yml down -v                # 彻底重置（清空数据卷，慎用）
```

---

## 七、调试提示

- **接口联调**：打开 `/doc.html`（Knife4j），可直接在页面上试调接口；先调 `/auth/login` 拿 token，再在右上角填入。
- **看健康状态**：`curl localhost:8080/actuator/health`。若为 `DOWN` 但服务正常，多半是某个可选中间件的健康探针没关——把对应的 `*_ENABLED` 设为 `false`。
- **前端请求 404**：检查后端接口真实路径。注意前端以 `/api` 为前缀，但后端本身的路径**不含** `/api`（前缀由代理去掉）。
- **判题不返回结果**：先确认判题机已启动（`--profile judge`），再看后端日志里的 Judge0 端点与熔断状态。

### 错误响应

现行协议、业务错误定义、前端错误读取及文件/SSE 适配见[错误响应约定](ERRORS.md)。普通接口继续按 body `code=200` 判断成功，失败原因使用稳定 `errorCode`；不能把未知异常消息直接返回给页面。
