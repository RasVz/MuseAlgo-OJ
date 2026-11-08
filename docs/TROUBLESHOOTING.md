# 常见问题与排错

按「症状 → 原因 → 处理」组织。

---

## 启动类

### 后端启动即失败，日志里有 `JWT_SECRET` 相关报错

**原因**：`JWT_SECRET` 未设置或长度不足 32 字节。这是**刻意的快速失败**设计——宁可起不来，也不要带着弱密钥上线。

**处理**：

```bash
openssl rand -base64 48          # 生成一个
# 填进 .env 的 JWT_SECRET=
```

### 日志出现 `Failed to open the referenced table 'user'`（Error 1824）

**原因**：MySQL 的**数据卷是空的**，而且没有执行表结构基线。

本项目从 V1 起的 Flyway 迁移是**增量迁移**——V1 的第一条外键引用了 `` `user` `` 表，而 `user` 表属于「引入 Flyway 之前的既有基线」，不在任何迁移文件里。所以空库直接跑 Flyway 必然失败。

**处理**：正常情况下 MySQL 容器首次启动会自动执行 `docker/mysql-init/00-schema.sql`。若你没看到它执行，通常是：

- 数据卷**早已存在**（初始化脚本只在数据卷为空时执行一次）→ 确认当前项目和卷的数据归属。只有确认可丢弃的本地合成开发数据，才按[开发指南](DEVELOPMENT.md#六构建与测试)中的重置命令操作；不要在生产或含有用数据的项目上执行 `down -v`；
- `00-schema.sql` 被误删或未被拷贝进工作目录 → 检查该文件是否存在。

新建卷还会导入 `01-reference-roles.sql` 建立基础角色，不会创建账号或业务数据；已有卷不会自动补跑 MySQL 初始化脚本。生产环境先用 [运维入口](OPERATIONS.md) 检查数据库状态，不要通过删除卷来补数据。

### 日志出现 Flyway `checksum mismatch`

**原因**：有人修改了**已执行过的**迁移文件（改了注释也算）。

**处理**：

- 先还原被修改的历史迁移；只有确认可丢弃、且已备份的本地开发库才考虑重置数据卷。重置会清空数据，不能替代还原迁移文件。
- 如果是生产库，**不要**用 `flyway repair` 掩盖问题，也不要手改 `flyway_schema_history`。正确做法是把改动还原（迁移文件必须保持字节级不变），把需要的修正写成**新的** `V{n+1}__说明.sql`。

> 已执行或已分发的迁移一律冻结，与仓库是否公开无关；只新增迁移，不改历史文件。详见 [DEVELOPMENT.md](DEVELOPMENT.md) 第 3.2 节。

在仓库根目录运行 `python3 scripts/check_migrations.py` 可发现本地迁移文件与 SHA-256 登记不一致；随后对照可信 Git 历史定位具体改动。不要同时改写迁移和登记清单来绕过检查，也不要用 `flyway repair` 掩盖历史校验错误。

### `ops.py status` 显示迁移入口不可用

**原因**：当前Compose选用的后端镜像不包含独立数据库维护入口，常见于旧版本镜像。

**处理**：核对 `BACKEND_IMAGE` 指向包含当前维护入口的发行镜像，再重新查询。状态检查失败时不会自动回退到业务启动入口执行迁移；不要为绕过错误而启动旧业务镜像写入未知结构。

### V63角色初始化失败或留下存储过程

**表现**：角色映射检查报告未知或冲突；Flyway报告V63失败；或重试时提示 `bootstrap_reference_roles` 已存在。

**原因与边界**：生产 `ops.py` 维护入口会在迁移前只读检查内置角色映射；开发应用启动的 Spring Flyway 路径则会直接执行V63。该迁移先创建MySQL存储过程，再在 `CALL` 中检查未知映射。MySQL过程内的 `ROLLBACK` 不会撤销此前的 routine DDL，失败时后面的 `DROP PROCEDURE` 也不会执行；Flyway 历史记录是否标记失败须按数据库中的实际记录判断，不能从应用日志推断。

**处理**：不要盲目重启后端或运行 `flyway repair`，也不要手动删除routine或清空数据卷。先停止自动重试并保存日志；在 `oj_db` 中只读检查角色、过程和V63历史：

```sql
SELECT id, code FROM sys_role
WHERE id IN (1, 2, 3) OR UPPER(code) IN ('STUDENT', 'TEACHER', 'ADMIN');
SELECT routine_name FROM information_schema.routines
WHERE routine_schema = 'oj_db' AND routine_name = 'bootstrap_reference_roles';
SELECT installed_rank, version, description, success FROM flyway_schema_history WHERE version = '63';
```

随后制作受控私有备份。`tests/flyway_failure_smoke.py` 的隔离验证会在注入故障前保存并校验 V53 数据库快照，再在新的 MySQL 项目恢复快照、确认合成记录保留并迁移到目标版本；这不包含 Compose 文件卷恢复。生产恢复仍须使用匹配的数据库与文件备份，在新的 Compose 项目和空卷按[运维指南](OPERATIONS.md#一致备份与恢复)恢复并验证；失败时保留原现场，并按验证证据及运维流程处理，不要盲目重试或覆盖原项目。验证入口与证据范围见[测试指南](TESTING.md#发行镜像演练)。

### 启动日志里的 WARN 需要处理吗？

后端启动时固定会输出下面几条 WARN，**都不影响运行**，`logback-spring.xml` 里已把它们降到 ERROR 级别，正常启动看不到：

| 日志 | 原因 |
|---|---|
| `ProblemContestMapper.insert / selectById / updateById / deleteById` `Has been loaded by XML ... ignoring this injection` | 这几个方法是**有意**用 `ProblemContestMapper.xml` 重写 SQL 的（带 `is_deleted = 0` 过滤），MyBatis-Plus 检测到同名语句后跳过自己的自动注入，行为以 XML 为准 |
| `OutboxEventMapper.deleteByIds` 同上 | 同理，XML 版本接收 `List<String> ids`，与 MP 内建签名不同 |
| `Can not find table primary key in Class: UserTagRating / ProblemTag`、`Not found @TableId annotation` | `problem_tag` 与 `user_tag_rating` 是**联合主键**（`problem_id + tag_id`、`user_id + tag_id`），MyBatis-Plus 只支持单列 `@TableId`。这两个 Mapper 全仓只用 `selectList` 与自定义 SQL，不调用 `selectById / updateById / deleteById`，所以无需处理 |

> 如果你自己新增了 Mapper 并看到 `is ignored, because it exists, maybe from xml file`（**ERROR** 级），那才是真问题：同一个 mapper id 被注解和 XML 重复定义，MyBatis 会丢弃注解版本。删掉其中一处即可。

运行期还可能看到 `JwtAuthenticationTokenFilter - JWT 校验失败: JWT expired`，这是**过期 token 正常被拒**的记录（WARN 级，保留），不是故障；前端会自动清除本地 token 并跳回登录页。

---

## 端口与网络

### 3307 / 6381 端口被占用

**原因**：这两个端口刻意避开了宿主原生的 3306 / 6379。占用的可能是另一套 OJ 容器栈。

**处理**：

```bash
nc -z 127.0.0.1 3307 && echo 被占用
lsof -nP -iTCP:3307 -sTCP:LISTEN          # macOS/Linux 查是谁
docker ps | grep 3307                     # 查是不是另一个容器
```

确认无用后停掉它，或改 `compose.dev.yml` 里的宿主端口映射。

### 前端能打开，但登录/所有接口都 404

**原因**：请求路径前缀不对。前端统一以 `/api` 为前缀，由 Vite（开发期）或 Nginx（生产）去掉前缀后转发给后端；**后端自身的接口路径不含 `/api`**。

**处理**：先直连后端确认真实路径：`curl -X POST localhost:8080/auth/login ...`，再检查代理配置。

### `docker compose` 报 stack 名冲突

**原因**：项目名、固定容器名和宿主端口都可能冲突。开发编排既设置了 `name: musealgo-dev`，也设置了固定的 `container_name` 与宿主端口。

**处理**：用不同的 `-p` 项目名可以隔离默认网络和命名卷，但仍需在本地编排副本中删除或更改固定 `container_name`，并更换被占用的宿主端口。用 `docker compose -p 你的项目名 -f 你的编排文件 config` 检查最终配置，再启动。先确认现有容器和数据归属，不要直接删除另一套栈。

---

## 依赖与构建

### 前端 `npm install` 卡住或超时

**原因**：直连 npm 官方源在国内网络下容易超时。

**处理**：

```bash
npm config set registry https://registry.npmmirror.com
```

Docker 开发栈已内置该源（`NPM_CONFIG_REGISTRY`），此问题通常只出现在本地原生开发。

### 本地 `npm run dev` 报 esbuild / rollup 平台不匹配

**原因**：`node_modules` 是在**另一个平台**（例如 Windows 或容器内 Linux）安装的，里面只有那个平台的二进制（`@esbuild/win32-x64` vs `@esbuild/darwin-arm64`）。

**处理**：

```bash
rm -rf node_modules package-lock.json   # 视情况
npm install
```

Docker 开发栈用了一个命名卷单独存 Linux 版 `node_modules`，与宿主目录隔离，因此容器与原生开发可以并存互不干扰。

### Maven 报 `No plugin found`、找不到同级模块，或父工程 `spring-boot:run` 失败

**原因**：这两种报错方向相反，取决于 `-am` 用错了阶段。

- **安装阶段漏了 `-am`**：`oj-admin` 依赖其他模块的 SNAPSHOT，只装它自己会报「找不到依赖」。
- **启动阶段多了 `-am`**：父工程不是可执行模块，`-am` 会让 Maven 先对父工程执行 `spring-boot:run` 而失败。

**处理**：安装要 `-am`，启动不要 `-am`：

```bash
cd OJ-Backend
./mvnw -pl oj-admin -am -DskipTests install   # 1) 先装同级模块
./mvnw -pl oj-admin spring-boot:run           # 2) 再启动
```

`./scripts/run-local.sh` 已经按这个顺序封装好了。

---

## 容器与资源

### 容器起来后不久就消失，退出码 137

**原因**：`137 = 128 + 9`，即被 OOM Killer 杀掉——内存不够。

**处理**：Docker Desktop 默认资源偏保守，调到 **4 CPU / 4 GB** 以上。若只有 2.5 GB，请**不要**同时起判题机（judge0 worker 单个约 700 MB）与 maven 编译。

### `/actuator/health` 恒为 `DOWN`，但服务其实正常

**原因**：某个**可选中间件**的健康探针开着，而你没有部署它（最常见是 RabbitMQ）。

**处理**：关掉对应探针。Docker 开发栈已内置 `MANAGEMENT_HEALTH_RABBIT_ENABLED=false`；原生部署时请按需设置，并把 `OJ_RABBITMQ_ENABLED` / `OJ_SEARCH_ENABLED` 设为 `false`。

---

## 判题相关

### 判题一直不返回结果 / 一直是「评测中」

按顺序排查：

1. **判题机起了吗**：`docker compose -f compose.dev.yml --profile judge up -d`（默认**不**启动）。
2. **后端能连到它吗**：`JUDGE0_URLS` 是否指向正确地址。容器网络内用服务名 `http://judge0-server:2358`。
3. **端点是否被熔断**：单端点连续失败 3 次会熔断冷却 60 秒，看后端日志有无熔断记录。
4. **在途上限是否过高**：`JUDGE0_MAX_INFLIGHT` **必须 ≤ Judge0 的 worker 槽位数**，否则并发用例在判题队列超时 → 重试 → 雪崩。
5. **看门狗是否回收**：卡住超过 10 分钟的提交会被看门狗回收重排。

### 判题机镜像太大 / 在 Mac 上特别慢

`judge0/judge0:1.13.1` 约 **14 GB**，且是 **linux/amd64** 架构——在 Apple Silicon 上通过 Rosetta 类模拟运行，**慢但可用**。生产环境建议使用 x86_64 服务器。

若正确程序报 `Caught fatal signal 5`，且 Judge0 的 stderr 含
`rosetta error: unable to mmap ExecutableHeap`，在 `.env` 设置
`JUDGE0_PER_PROCESS_MEMORY_LIMIT=false` 后重建后端容器。
这会保留题目的 `memory_limit`，将内存限制从每进程地址空间切换到 Judge0 聚合限制。
应在目标 Judge0 环境验证该模式可用；默认仍为 `true`，保留既有部署行为。

### 判题机能暴露到公网吗

**不能。** Judge0 需要 `privileged` 权限且默认无鉴权，等于把任意代码执行能力开放出去。只对后端所在网络开放。

---

## 演示数据与登录

### 导入演示数据后中文乱码

MySQL 服务端为 utf8mb4 并不能保证客户端按相同编码读取 SQL。
`scripts/db-seed.sh` 显式使用 `--default-character-set=utf8mb4`，`seed.sql` 也声明了 `SET NAMES utf8mb4`。
已错误导入的数据不会自动恢复；请在备份后重新导入到专用演示库，勿直接重复导入已有业务库。

### 导入 `seed.sql` 后登录报 `Bad credentials`

**原因**：`seed.sql` 里的用户口令是 bcrypt 哈希，若哈希与文件头标注的明文口令（`123456`）不匹配就会登录失败。

**处理**：确认使用仓库当前的 `seed.sql`。若自行改过用户数据，请用后端重新生成哈希，或到 `/register` 注册新账号。

### 库是空的，没有账号

**处理**：两种方式——执行 `./scripts/db-seed.sh` 导入演示数据（含 `admin / 123456`），或直接到 `/register` 注册，再手工提权。

⚠️ **生产环境不要导入演示账号。**

---

## 课件相关

### 课件列表能打开，但 iframe 里是白屏

按顺序排查：

1. 课件产物是否存在：`oj.slidev.output-dir`（默认工作区根下的 `OJ-Coursewares`）里应有以 uuid 命名的目录。
2. **uuid 目录名不可改**：`courseware` 表里的 `url` 字段是 `/coursewares/<uuid>/index.html`，改目录名会直接断链。
3. **工作区根识别**：后端会「从启动目录向上找同时含 `OJ-Backend` 与 `OJ-Slidev` 的目录」来定位工作区根。Docker 开发栈为此把整个仓库挂载进容器；**找不到时不再报错中断启动**，而是退回「进程工作目录」，单 jar / 生产镜像部署请显式给绝对路径（见下一条）。
4. 静态路径映射是否生效：`/coursewares/` 应能直接访问到文件（开发期由 Vite 代理，生产由 Nginx 转发）。

### 生产镜像 / 单 jar 部署后，课件功能不可用

课件生成的做法是「以外部进程调用 Slidev」，因此运行环境必须同时具备三样东西：
**Node 运行时**、**`OJ-Slidev` 工程**、**装好的 `OJ-Slidev/node_modules`**。缺任何一样都会失败。

- **用仓库自带的 `deploy/Dockerfile.backend` 构建出的镜像已经具备**：镜像里包含 Node 22 与
  `OJ-Slidev`（已 `npm ci`），并预设了 `OJ_SLIDEV_WORKER_DIR=/app/OJ-Slidev`，无需额外配置。
- **systemd / 单 jar 部署需要自己准备**：发行形态里没有源码目录树，「工作区根推断」必然失败，
  必须显式给绝对路径：

```bash
OJ_SLIDEV_WORKER_DIR=/opt/musealgo/OJ-Slidev       # 该目录需存在且已执行 npm ci
OJ_SLIDEV_OUTPUT_DIR=/opt/musealgo/OJ-Coursewares  # 课件产物输出目录
```

课件输出目录要挂持久卷，并与前端 Nginx 的 `/coursewares/` 指向同一份。
不需要在线生成课件时，只把课件产物目录挂进前端容器，即可正常**浏览**已有课件。

---

## 前端功能

### AI 助教不回复 / 一直转圈

**原因**：`DEEPSEEK_API_KEY` 未配置，或接口地址不可达（部分托管通道在海外，服务器需要有对应出口）。

**处理**：配置 `DEEPSEEK_API_KEY`；如使用自建或第三方通道，同时设置 `DEEPSEEK_API_URL` 与 `AI_MODEL`（后者需与服务商的模型 ID 完全一致）。

> 注：AI 功能缺失时**其余功能完全不受影响**，属于设计的降级行为。

### 家长报告导不出图片

**原因**：报告由后端生成受权 HTML（`report_job.html_content`），导出 PNG 是浏览器在隔离 iframe 内本地截图，
不需要服务端 Chromium，也没有 `OJ_CHROMIUM_URL` 这类配置。常见失败原因是报告尚未加载完成（图表未就绪）、
浏览器拦截下载，或报告内容超出前端截图上限。

**处理**：

- 等待详情弹窗里的报告完全渲染（雷达图出现）后再点「导出 PNG」；
- 被浏览器拦截下载时，允许该站点下载后重试，或改用「另存为 HTML」；
- 需要 PDF 时用浏览器打印（打印样式已内置），不要为此部署 browserless/Chromium。

### 上传的图片/头像打不开（404）

**原因**：未配置对象存储时，后端把文件落在本地并返回 `/uploads/<uuid>.<ext>` **相对地址**，
需要由前端把 `/uploads/` 转发到后端，这条链路缺了就会 404。

**处理**：

- 开发期：Vite 已配置 `/uploads` 代理（见 `OJ-Frontend/vite.config.js`），确认后端在 8080 上即可；
- 生产：前端容器需要把上传卷**只读**挂到 `/var/www/uploads`，且 Nginx 有 `/uploads/` 路由
  （见 `deploy/nginx.conf` 与 `docker-compose.prod.yml`）。自建 Nginx 时请照抄
  [DEPLOYMENT.md](DEPLOYMENT.md) 第四节的 `location /uploads/` 段。
