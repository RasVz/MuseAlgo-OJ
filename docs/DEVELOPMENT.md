# 开发指南

> 面向贡献者。提交规范与 PR 流程见 [CONTRIBUTING.md](../CONTRIBUTING.md)。

## 一、环境要求

| 工具 | 版本 | 说明 |
|---|---|---|
| JDK | **17**（release 17） | 21 亦可运行；不要用 24+ |
| Maven | 3.9+ | 仓库自带 `mvnw` 包装器，无需单独安装 |
| Node.js | `^20.19.0 \|\| >=22.12.0` | 前端要求（见 `OJ-frontend/package.json` 的 `engines`） |
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

首次启动 MySQL 会自动执行 `docker/mysql-init/00-schema.sql`（表结构基线），后端随后启动并通过 Flyway 校验。可用以下地址：

- 前端 http://localhost:5173 ｜ 后端 http://localhost:8080
- **接口文档 http://localhost:8080/doc.html**
- 健康检查 http://localhost:8080/actuator/health

**导入演示数据**（可选）：`./scripts/db-seed.sh`，之后可用 `admin / 123456` 登录。

**需要判题时**：`docker compose -f compose.dev.yml --profile judge up -d`（镜像约 14 GB，首次拉取较久）。

> 后端源码整仓挂载，改完 Java 代码执行 `docker compose -f compose.dev.yml restart backend` 即可；前端有 HMR，改动即时生效。

### 路线 B：本地原生

需要自备 JDK 17、Maven、MySQL 8、Redis、Node.js。

```bash
# 后端（脚本会自动 source .env，先把各模块装进本地仓库，再启动 oj-admin）
./scripts/run-local.sh

# 或手动分两步，顺序不能颠倒：
# 1) 先把同级模块装进本地 Maven 仓库，否则 oj-admin 找不到它们的 SNAPSHOT
export JWT_SECRET="$(openssl rand -base64 48)"
export OJ_DB_PASS=你的数据库密码
cd OJ-backend && ./mvnw -pl oj-admin -am -DskipTests install
# 2) 再启动。这一步**不要**加 -am：父工程不是可执行模块，
#    带 -am 会让 Maven 先对父工程执行 spring-boot:run 而失败
./mvnw -pl oj-admin spring-boot:run

# 前端（/api 与 /uploads 默认代理到 localhost:8080）
cd OJ-frontend && npm install && npm run dev
```

> 概括一下这条容易踩的规则：**安装阶段要 `-am`，启动阶段不要 `-am`。**

---

## 三、数据库迁移（必读）

这是本项目最容易踩坑的地方，请务必读完。

### 3.1 迁移文件的位置与命名

`OJ-backend/oj-admin/src/main/resources/db/migration/`，命名 `V{n}__说明.sql`，当前已到 `V53`。

### 3.2 ⚠️ 已发布的迁移文件不要修改

Flyway 的 checksum 覆盖**文件全部字节**，**连改注释、改空行都会让已有数据库在启动时报校验失败**。需要修正时，请新增一个 `V{n+1}__说明.sql`，不要改动已有文件。

**唯一的例外是「首次公开发布之前」**：此时还没有任何外部数据库应用过这些迁移，改完不会影响别人。
这种情况下必须按 `docker/mysql-init/README.md` 的步骤同步订正 `00-schema.sql` 里
`flyway_schema_history` 的 checksum，否则新克隆者会因「基线历史与迁移文件不匹配」而起不来。
**仓库一旦公开发布，这条例外即失效**——之后一律新增迁移，不再修改历史文件。

### 3.3 这套迁移是「增量迁移」，不是自足建库脚本

`application.yml` 里配置了：

```yaml
flyway:
  baseline-on-migrate: true
  baseline-version: 6
```

含义是：**V1–V6 属于「引入 Flyway 之前的既有基线」**，Flyway 只执行 V7 及之后的增量。V1 虽然写了 `CREATE TABLE IF NOT EXISTS`，但它的第一条外键就引用了 `` `user` `` 表——而 `user` 表**不在任何迁移文件里**。因此：

> **空库直接跑 Flyway 必然失败**（`Failed to open the referenced table 'user'`）。

所以仓库用 `docker/mysql-init/00-schema.sql` 承担「结构基线」的角色：它由开发库导出，包含 59 张表的**纯结构**与 48 条 Flyway 迁移记录（**不含任何业务数据**），MySQL 首次启动时自动执行。这样后端启动时 Flyway 校验通过并直接跳过已应用的迁移。

`OJ-backend/db/tables/*.sql` 是历史参考快照，**已过时且不完整（缺 20 张表）**，不要拿它当基线用。

### 3.4 新增迁移的正确姿势

```bash
# 1. 在 db/migration/ 下新建 V54__你的改动.sql
# 2. 重启后端，Flyway 自动执行
docker compose -f compose.dev.yml restart backend
docker compose -f compose.dev.yml logs -f backend | grep -i flyway
```

**如果你改了表结构基线**（例如新增了表并希望新克隆者也能用），需要同步重导 `00-schema.sql`——否则新克隆者拿到的基线会缺表，而 Flyway 因为 baseline 机制不会补。重导方式：

```bash
docker exec <mysql容器> mysqldump -uroot -p<密码> --no-data --skip-comments \
  --no-tablespaces --set-gtid-purged=OFF oj_db > /tmp/schema.sql
docker exec <mysql容器> mysqldump -uroot -p<密码> --no-create-info --skip-comments \
  --no-tablespaces --set-gtid-purged=OFF --skip-extended-insert --complete-insert \
  oj_db flyway_schema_history > /tmp/history.sql
# 将两者按 00-schema.sql 的头部注释格式合并（结构 + 逐行 INSERT 历史记录）
```

---

### 3.5 演示判题用例的存放与安装

`problem_case` 表里存的是**相对路径**（`<problemId>/<caseId>/input.txt`），真实内容在文件系统上，
根目录由 `oj.testcase.base-path` 决定（开发栈为 `OJ-backend/data/testcases`）。

演示用例的源文件在 `OJ-backend/db/demo-testcases/`，其期望输出由
`scripts/gen-demo-testcases.py` 里的参考实现计算得出，可用 `--check` 复核是否与文件一致。
`./scripts/db-seed.sh` 会先把用例复制到用例目录，再导入 SQL。

> **不要只执行 `seed.sql`**：那样库里只有路径、盘上没有文件，判题会因读不到用例而失败。

## 四、工程结构

```
OJ-backend/                 12 个 Maven 模块（详见 docs/ARCHITECTURE.md）
├── oj-admin/               唯一启动模块 + Flyway 迁移 + 跨域 Controller
├── oj-api/                 跨模块契约（判题网关、AI 接口、只读查询 DTO）
├── oj-<业务域>/             各业务模块（system / problem / judge / contest / ...）
├── db/                     seed.sql（演示数据）与 tables/（过时快照，参考用）
└── pom.xml                 父工程

OJ-frontend/                Vue 3 + Vite 单页应用
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
2. 写操作必须落在 **`top.everblue.oj.<module>.application` 应用服务**里，由它负责权限校验、事务边界、字段白名单复制。
3. **不要**在 Controller 里直接调 Mapper，也**不要**跨模块访问别人的 Mapper——跨域读取走 `oj-api` 里的只读查询接口。
4. 这些规则由 ArchUnit 测试强制校验，越界会让 `mvn test` 失败。

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
5. 可选配置在 `.env.example` 里请**整行注释掉**，不要写成 `KEY=`。
   留空赋值会覆盖 `application.yml` 里的 `${KEY:默认值}`，把默认值抹成空字符串
   （`AI_MODEL`、`DEEPSEEK_API_URL` 都踩过这个坑）。

### 5.4 修改种子数据

`OJ-backend/db/seed.sql` 是**演示数据**，导入后立即可用 `admin / 123456` 登录。修改时请注意：

- 表结构以 Flyway 迁移为最终准绳。历史上这里踩过坑：`V52` 已把 `submission.code` 拆到 `submission_code` 表，而 `seed.sql` 仍按旧结构插入，导致导入中断、后续种子数据全部丢失。
- 用户口令是 **bcrypt 哈希**，必须与文件头标注的明文口令（`123456`）真实匹配，否则「导入了却登不进去」。

---

## 六、构建与测试

```bash
# 后端：编译（快）
cd OJ-backend && ./mvnw -q compile

# 后端：跑单元测试
cd OJ-backend && ./mvnw test

# 后端：打包（会先跑一遍单元测试）
cd OJ-backend && ./mvnw package
# 产物：OJ-backend/oj-admin/target/oj-admin-0.1.0-SNAPSHOT.jar

# 后端：连需要数据库的集成测试一起跑（需本机 MySQL / Redis 已就绪）
# 该 profile 会清空默认排除项，并显式 include **/*IT.java，
# 因此单元测试与集成测试都会被执行
cd OJ-backend && ./mvnw test -Pintegration

# 前端：代码检查（lint:ci 不带 --fix，与 CI 一致）
cd OJ-frontend && npm run lint:ci

# 前端：本地开发时可用带自动修复的版本
cd OJ-frontend && npm run lint

# 前端：构建
cd OJ-frontend && npm run build
```

**测试分两层**：

| 层 | 内容 | 运行条件 |
|---|---|---|
| 单元测试 | 214 个用例，含 Mockito 打桩的领域逻辑与 Service 测试 | 无外部依赖，**CI 默认执行** |
| 集成测试 | `OjApplicationTests`（容器能否启动）、`LastAdminConcurrencyIT`（并发管理操作） | 需要 MySQL / Redis，已由 `pom.xml` 默认排除；`-Pintegration` 会清空排除项并显式 include `**/*IT.java`（默认命名规则不含 `*IT`，只清 excludes 是选不中的） |

> 默认排除集成测试而不是让 `mvn test` 直接失败，是有意的：否则所有人（包括 CI）都会用
> `-DskipTests` 把测试整体跳过，判题、榜单、查重等核心逻辑被改坏也不会被发现。
>
> 位于 `oj-admin` 的 `ModuleDependencyArchTest`（ArchUnit）负责校验模块依赖规则，
> 属于单元测试层，改动模块依赖关系后 CI 会自动拦下。

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
