# 生产部署指南

> 本文面向**自建站点**的运维者与老师。项目**不是**开箱即用的 SaaS——你需要自己准备服务器、域名与数据库。

## 〇、部署前请先明确三件事

1. **这是教学系统，不是高并发商业 OJ。** 单机部署（2 核 4G 起）足以支撑一个班级到一所学校的规模。
2. **判题机有安全风险。** Judge0 需要 `privileged` 权限执行用户代码，**绝对不能暴露到公网**，详见第五节。
3. **密钥必须重新生成。** 仓库里不含任何真实密钥，`.env.example` 只是变量名的模板。

## 一、组件与端口

| 组件 | 必需 | 说明 |
|---|---|---|
| MySQL 8 | ✅ | 主数据库 `oj_db` |
| Redis | ✅ | 缓存、榜单、限流、JWT 黑名单 |
| 后端（oj-admin） | ✅ | 单端口 HTTP 服务。官方镜像内已含 Node 22 与 `OJ-Slidev`（在线构建课件用） |
| 前端（静态产物） | ✅ | 由 Nginx 等直接托管 |
| Judge0 | 判题必需 | 独立容器栈，HTTP 调用 |
| RabbitMQ | 可选 | 缺失时自动回退数据库轮询 |
| Meilisearch | 可选 | 缺失时搜索回退 `LIKE` |
| headless Chromium | 可选 | 仅「家长报告导出」的图片渲染需要 |

后端对外只需暴露 **8080** 一个端口。MySQL/Redis 建议只监听 `127.0.0.1`，不要开公网。

---

## 二、方式 A：Docker 单机部署

仓库自带两份**生产用** Dockerfile（`deploy/` 目录），与开发栈完全分开：开发栈在容器里跑 Maven 与 Vite dev server，生产镜像则是「编译好的产物 + 精简运行时」。

### 2.1 构建镜像

> 构建上下文必须是**仓库根目录**（Dockerfile 里要复制 `OJ-backend/`、`OJ-frontend/`）。

```bash
# 后端（多阶段：Maven 编译 → JRE 运行）
docker build -f deploy/Dockerfile.backend -t musealgo-oj/backend:latest .

# 前端（多阶段：Vite 构建 → Nginx 托管）
docker build -f deploy/Dockerfile.frontend -t musealgo-oj/frontend:latest .

# 国内网络较慢时指定镜像源
docker build -f deploy/Dockerfile.backend -t musealgo-oj/backend:latest \
  --build-arg MAVEN_MIRROR=https://maven.aliyun.com/repository/public \
  --build-arg NPM_REGISTRY=https://registry.npmmirror.com .
docker build -f deploy/Dockerfile.frontend -t musealgo-oj/frontend:latest \
  --build-arg NPM_REGISTRY=https://registry.npmmirror.com .
```

后端镜像内含 **JRE 17 + Node 22 + `OJ-Slidev`（已 `npm ci`）**。课件系统是以「外部进程」方式
调用 Slidev 的（见 `oj-teaching` 的 `SlidevBuilderService`），所以运行环境必须能执行
`OJ-Slidev/node_modules/.bin/slidev`；镜像已经把这件事做好，并预设了
`OJ_SLIDEV_WORKER_DIR=/app/OJ-Slidev`。若你只想要最小运行时、不需要在线生成课件，
可以改走第三节的 systemd 单 jar 方案（只需 JRE）。

前端镜像内置的 Nginx 配置（`deploy/nginx.conf`）已把 `/api` 反向代理到 **`backend:8080`**——也就是说它期望后端在同一 Docker 网络里且服务名为 `backend`。若你的拓扑不同，请改 `proxy_pass`。

依赖预取是可跳过的加速步骤，默认最长180秒；网络环境下预取缓慢时可用 `--build-arg MAVEN_PREFETCH=false` 直接进入正常编译。编译仍按锁定的Maven依赖解析，不跳过依赖或复用旧代码。网络超时参数参照 [Maven Resolver 1.9 配置](https://maven.apache.org/resolver-archives/resolver-1.9.22/configuration.html)。

### 2.2 编排与启动

仓库根目录的生产编排文件 [`docker-compose.prod.yml`](../docker-compose.prod.yml) 包含五个服务：

| 服务 | 镜像 | 端口 | 说明 |
|---|---|---|---|
| `mysql` | `mysql:8.0` | 不映射 | 首次启动自动执行 `docker/mysql-init/00-schema.sql` 建基线 |
| `redis` | `redis:6.0` | 不映射 | AOF 持久化 |
| `migrate` | 后端发行镜像 | 不映射 | 独立DDL账号一次性运行Flyway，成功后启动业务 |
| `backend` | `musealgo-oj/backend:latest` | `127.0.0.1:8080` | 默认只绑本机，供运维探活 |
| `frontend` | `musealgo-oj/frontend:latest` | `80`（`WEB_PORT` 可改） | Nginx 托管静态产物 + `/api` 反代后端 + `/coursewares`、`/uploads` 静态目录 |

前端容器会把 `coursewares-data` 与 `uploads-data` 两个卷**只读**挂到 `/var/www/coursewares`、
`/var/www/uploads`，分别对应后端的课件产物目录与本地上传目录。
这样即使不使用对象存储，用户上传的图片也能通过 `/uploads/<uuid>.<ext>` 被浏览器访问。

```bash
cp .env.example .env
vi .env                 # 填 JWT_SECRET、OJ_DB_PASS、OJ_DB_ROOT_PASS、OJ_DB_MIGRATION_PASS
chmod 600 .env

docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml ps
curl -fsS localhost:8080/actuator/health        # 期望 {"status":"UP"}
```

后端健康检查通过的含义是**完整链路已就绪**：MySQL结构初始化 → migrate独立进程迁移成功 → 应用以CRUD账号连接并启动。任一环失败，健康检查都不会是 `UP`。

常用运维命令：

```bash
docker compose -f docker-compose.prod.yml logs -f backend     # 看后端日志
docker compose -f docker-compose.prod.yml exec -T mysql sh -c \
  'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" oj_db -e "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"'
```

> **注意**：首次启动时 MySQL 会自动执行 `docker/mysql-init/00-schema.sql` 建立表结构基线与 Flyway 历史（**不含任何业务数据**）。演示数据请用 `scripts/db-seed.sh`，生产环境**不要**导入演示账号。
>
> `db-seed.sh` 同时会把 `OJ-backend/db/demo-testcases/` 里的演示判题用例安装到
> `OJ-backend/data/testcases/`。生产环境通常不需要这一步——你有自己的题目与用例，
> 用后台的 ZIP/FPS 导入即可。但如果你手动执行了 `seed.sql` 却没安装用例文件，
> 判题会因读不到用例而失败，这点在排查时容易漏掉。
>
> 生产编排与开发编排使用**不同的 compose 项目名**（`musealgo-prod` / `musealgo-dev`），数据卷互不干扰，不会串库。
>
> 查询命令在容器内读取 `MYSQL_ROOT_PASSWORD`，无需把 `.env` 导出到宿主 shell。
> 如果你不打算在服务器上编译，可以按第三节从仓库根目录构建 jar 与前端，再复制到服务器。

---

## 三、方式 B：systemd 单机 jar

本轮运行演练以Compose为准；原生systemd命令需在目标Linux发行版上单独验收。

适用于使用 systemd 的 Linux 服务器。先安装并启动 MySQL 8、Redis，配置它们只监听本机；
服务器安装 JRE 17，构建环境另需 JDK 17 与 Node.js（建议 22.12+）。MySQL/Redis 的安装方式、
服务名和 Java 路径按发行版调整。以下构建、复制命令从服务器上的仓库根目录执行。

### 3.1 初始化原生数据库

**仅对全新的空库执行**，已有库按升级流程处理。先以管理员进入 MySQL：

```bash
mysql -uroot -p
```

在 MySQL 提示符中执行，先把示例口令替换成自己的强口令：

```sql
CREATE DATABASE oj_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'musealgo_migrator'@'127.0.0.1' IDENTIFIED BY 'REPLACE_WITH_MIGRATION_PASSWORD';
GRANT ALL PRIVILEGES ON oj_db.* TO 'musealgo_migrator'@'127.0.0.1';
CREATE USER 'musealgo_app'@'127.0.0.1' IDENTIFIED BY 'REPLACE_WITH_APPLICATION_PASSWORD';
GRANT SELECT,INSERT,UPDATE,DELETE ON oj_db.* TO 'musealgo_app'@'127.0.0.1';
EXIT;
```

从仓库根目录导入结构基线；命令会提示输入专用账号的口令：

```bash
mysql --default-character-set=utf8mb4 -h127.0.0.1 -umusealgo_migrator -p oj_db < docker/mysql-init/00-schema.sql
```

基线包含结构与迁移历史，不含业务数据。只有迁移账号需要本库DDL权限，不授予全局权限；长驻后端使用CRUD账号。
生产环境不导入演示账号。Redis 如果设置了口令，后续环境文件也要配置 `OJ_REDIS_PASS`。

### 3.2 构建、账号与目录

```bash
(cd OJ-backend && ./mvnw package)
(cd OJ-frontend && npm ci && npm run build)

# Linux 示例：账号已存在时跳过 useradd；nologin 路径按发行版调整
sudo useradd --system --user-group --home-dir /opt/musealgo --shell /usr/sbin/nologin oj
sudo mkdir -p /opt/musealgo/data/testcases /opt/musealgo/data/uploads \
  /opt/musealgo/OJ-Coursewares /opt/musealgo/dist
sudo install -m 644 OJ-backend/oj-admin/target/oj-admin-0.1.0-SNAPSHOT.jar /opt/musealgo/oj-admin.jar
sudo cp -R OJ-frontend/dist/. /opt/musealgo/dist/
sudo chown -R oj:oj /opt/musealgo/data /opt/musealgo/OJ-Coursewares
```

前端静态产物和 jar 由 root 管理，运行账号只需读取；数据与课件输出目录由 `oj` 写入。
升级时按实际产物版本更换 jar 文件名。

### 3.3 环境文件与服务

创建仅 root 可读的配置文件（systemd 由系统管理器读取，再注入服务）：

```bash
sudo install -d -m 700 /etc/musealgo
sudo install -m 600 /dev/null /etc/musealgo/oj.env
sudoedit /etc/musealgo/oj.env
```

首次安装时将以下模板写入文件，替换密钥和数据库口令；升级时直接编辑原文件，不要执行上面的空文件创建命令。
`JWT_SECRET` 可用 `openssl rand -base64 48` 生成。该文件使用 systemd EnvironmentFile 语法，不是 shell 脚本。

```dotenv
JWT_SECRET=REPLACE_WITH_GENERATED_RANDOM_SECRET
OJ_DB_HOST=127.0.0.1
OJ_DB_USER=musealgo_app
OJ_DB_PASS=REPLACE_WITH_APPLICATION_PASSWORD
SPRING_FLYWAY_ENABLED=false
SPRING_PROFILES_ACTIVE=prod
OJ_API_DOCS_PUBLIC=false
TZ=Asia/Shanghai
OJ_REDIS_HOST=127.0.0.1
# Redis 启用认证时取消注释并填写：
#OJ_REDIS_PASS=REPLACE_WITH_YOUR_REDIS_PASSWORD
OJ_RABBITMQ_ENABLED=false
OJ_SEARCH_ENABLED=false
MANAGEMENT_HEALTH_RABBIT_ENABLED=false
# 与第四节同源 Nginx 配套，必须显式留空：
OJ_PUBLIC_BASE_URL=
SERVER_ADDRESS=127.0.0.1
OJ_TESTCASE_PATH=/opt/musealgo/data/testcases
OJ_UPLOAD_DIR=/opt/musealgo/data/uploads
OJ_SLIDEV_WORKER_DIR=/opt/musealgo/OJ-Slidev
OJ_SLIDEV_OUTPUT_DIR=/opt/musealgo/OJ-Coursewares
DEEPSEEK_API_KEY=
```

原生默认会启用 RabbitMQ 与 Meilisearch；本模板显式关闭，并关闭 RabbitMQ 健康探针。
若以后启用它们，需同时配置连接参数与实际服务。Redis 若使用认证，填写 `OJ_REDIS_PASS`。
判题功能另需在此文件中配置第五节的 `JUDGE0_URLS`。

创建 `/etc/systemd/system/musealgo-oj.service`：

```ini
[Unit]
Description=MuseAlgo-OJ
After=network.target

[Service]
User=oj
Group=oj
WorkingDirectory=/opt/musealgo
EnvironmentFile=/etc/musealgo/oj.env
ExecStart=/usr/bin/java -Xmx2g -jar /opt/musealgo/oj-admin.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

首次安装或更换jar后，先在可信运维shell中运行独立迁移入口，口令不写进命令参数或运行环境文件：

```bash
read -r -s OJ_DB_MIGRATION_PASS
export OJ_DB_MIGRATION_PASS
OJ_DB_HOST=127.0.0.1 java -cp /opt/musealgo/oj-admin.jar \
  -Dloader.main=top.everblue.oj.FlywayMigrationRunner \
  org.springframework.boot.loader.launch.PropertiesLauncher
unset OJ_DB_MIGRATION_PASS
```

迁移成功并确认MySQL/Redis就绪后，再启动后端；需要自动依赖管理时将发行版对应的数据库/缓存服务名加入 `After=` 与 `Requires=`。

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now musealgo-oj
sudo systemctl status musealgo-oj
curl -fsS http://127.0.0.1:8080/actuator/health
```

换密钥时编辑 `/etc/musealgo/oj.env`，再执行 `sudo systemctl restart musealgo-oj`，无需重新打包。

### 3.4 在线课件构建（可选）

宿主机安装 **Node.js 22.12+**，确保服务的 PATH 能找到 `node` 与 `npm`。从仓库根目录复制工程：

```bash
sudo mkdir -p /opt/musealgo/OJ-Slidev
sudo cp OJ-Slidev/package.json OJ-Slidev/package-lock.json /opt/musealgo/OJ-Slidev/
sudo chown -R oj:oj /opt/musealgo/OJ-Slidev
sudo -u oj env npm_config_cache=/opt/musealgo/OJ-Slidev/.npm-cache \
  npm --prefix /opt/musealgo/OJ-Slidev ci --omit=dev
```

在线课件构建只需要工程依赖；`oj` 必须能在工程目录中写入临时 Markdown。
若使用 `/usr/local/bin` 等非默认 Node 安装路径，在服务中显式设置对应 `Environment=PATH=...` 并重启。
PDF/PPTX 导出另需完整开发依赖及 Chromium，见 [OJ-Slidev 说明](../OJ-Slidev/README.md)。
不需要在线构建时可跳过此节；已有课件仍可由 Nginx 静态托管。

---

## 四、Nginx 反向代理

前端是单页应用，`/api` 转发给后端、课件目录也要能访问：

```nginx
server {
    listen 80;
    server_name oj.example.com;
    client_max_body_size 128m;          # 与后端 multipart 上限保持一致

    root /opt/musealgo/dist;
    index index.html;

    # 单页应用：找不到文件回落到 index.html
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 后端接口（前端统一以 /api 为前缀）
    location /api/ {
        proxy_pass http://127.0.0.1:8080/;   # 去掉 /api 前缀
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_http_version 1.1;              # AI 流式输出需要
        proxy_set_header Connection "";
        proxy_buffering off;                 # 流式输出不缓冲
    }

    # 课件静态目录（oj.slidev.output-dir 指向的位置）
    location /coursewares/ {
        alias /opt/musealgo/OJ-Coursewares/;
    }

    # 本地上传文件（未配置对象存储时后端返回 /uploads/<uuid>.<ext>）
    # 上传目录放的是用户内容，即使后端已做扩展名白名单与魔数校验，
    # 也不要让 Nginx 有机会把它当脚本解释，因此先拒绝危险后缀再放行。
    location ~* ^/uploads/.*\.(php|phtml|phar|jsp|jspx|asp|aspx|cgi|pl|py|rb|sh|bash|jar|class|exe|dll|so|html?|xhtml|svg|svgz)$ {
        deny all;
    }
    location /uploads/ {
        alias /opt/musealgo/data/uploads/;
        expires 7d;
        add_header X-Content-Type-Options nosniff always;
        autoindex off;
    }
}
```

> `OJ_PUBLIC_BASE_URL` 用于生成本地上传文件地址。若浏览器从独立资源域名访问上传文件，
> 填该资源服务的对外基础地址，并确保它实际托管 `/uploads/`。
> 同源 Nginx 托管时显式设置 `OJ_PUBLIC_BASE_URL=`，返回 `/uploads/...` 相对地址。
> 两份 Compose 已覆盖为空；原生/systemd 若完全未设置，应用默认值是 `http://localhost:8080`，不能把「未设置」当作「留空」。

---

## 五、判题机（Judge0）部署要点

判题机需要 **真实执行用户提交的代码**，安全要求与普通服务不同：

1. **不要暴露到公网**。Judge0 默认无鉴权，端口只对后端所在网络开放。
2. **worker 需要 `privileged` 与 docker.sock**，因此最好单独一台机器或单独一个 compose 项目，与主站隔离。
3. **多端点与熔断**：后端支持逗号分隔的多个判题机地址，请求轮询分发，单端点连续失败 3 次后熔断冷却 60 秒。扩容 worker 数量后，记得同步调整后端的 `JUDGE0_MAX_INFLIGHT`——它**必须小于等于 Judge0 的 worker 槽位数**，否则并发用例会在判题队列超时并引发重试风暴。
4. **Apple Silicon 注意**：`judge0/judge0:1.13.1` 是 `linux/amd64` 镜像，在 M 系列 Mac 上走模拟运行，速度明显偏慢；生产建议用 x86_64 服务器。

后端只需一个环境变量即可接入：

```bash
JUDGE0_URLS=http://<judge0 地址>:2358
# 多个端点：http://10.0.0.11:2358,http://10.0.0.12:2358
```

---

## 六、密钥管理

| 变量 | 必填 | 说明 |
|---|---|---|
| `JWT_SECRET` | ✅ | JWT 签名密钥，≥32 字节随机串。**缺失或过短应用启动即失败**（刻意快速失败） |
| `OJ_DB_PASS` | ✅ | 数据库口令，缺失启动即报错 |
| `OJ_DB_HOST` / `OJ_DB_USER` | ⭕ | 默认 `127.0.0.1` / `root` |
| `OJ_REDIS_HOST` / `OJ_REDIS_PASS` | ⭕ | Redis 地址与口令 |
| `DEEPSEEK_API_KEY` | ⭕ | 留空时 AI 功能降级，其余不受影响 |
| `DEEPSEEK_API_URL` / `AI_MODEL` | ⭕ | 兼容 OpenAI 格式的接口地址与模型名 |
| `COS_SECRET_ID` / `COS_SECRET_KEY` | ⭕ | 对象存储。**未配置时自动降级为本地磁盘存储**，文件落在 `OJ_UPLOAD_DIR`，因此还需保证 Nginx 有 `/uploads/` 路由（见第四节） |
| `OJ_TESTCASE_PATH` | ⭕ | 判题用例目录，**生产请指向持久卷**（默认 `./data/testcases`） |
| `OJ_UPLOAD_DIR` | ⭕ | 本地上传目录（默认 `uploads`），与 Nginx 的 `/uploads/` 必须是同一份 |
| `OJ_SLIDEV_WORKER_DIR` / `OJ_SLIDEV_OUTPUT_DIR` | ⭕ | 课件工程目录与课件输出目录。官方镜像已设好默认值；**systemd / 单 jar 部署必须给绝对路径**（发行形态没有源码目录树，工作区根推断不成立），且 `OJ_SLIDEV_WORKER_DIR` 需先执行过 `npm ci` |
| `OJ_PUBLIC_BASE_URL` | ⭕ | 生成本地上传文件地址用的基础地址。**显式空值表示同源相对地址**；Compose 已覆盖为空，原生未设置时默认 `http://localhost:8080`；资源不同源时填实际资源基础地址 |
| `OJ_RABBITMQ_ENABLED` | ⭕ | 原生默认 `true`；无 broker 时设为 `false`，回退数据库轮询，并设 `MANAGEMENT_HEALTH_RABBIT_ENABLED=false` |
| `OJ_SEARCH_ENABLED` | ⭕ | 原生默认 `true`；无 Meilisearch 时设为 `false`，搜索回退 `LIKE` |

**换 JWT 密钥的后果**：所有已登录用户的 token 立刻失效，需要重新登录。请在低峰期操作。

**换大模型 Key 的流程**：先在平台创建新 key → 改环境变量 → 重启服务 → 冒烟测试（AI 对话能正常回复）→ 回平台**删除旧 key**（这样即使旧 key 曾泄漏也会立即失效）。

---

## 七、升级、备份、恢复与回滚

完整命令与权限边界见 [运维指南](OPERATIONS.md)。生产迁移已从业务启动拆到一次性 migrate 服务；默认业务账号无DDL权限。已有卷须先按指南配置账号，不能仅更换环境变量。

升级前用 `python3 scripts/ops.py backup --output /srv/oj-backups/<时间>` 暂停业务并保存数据库、用例、上传及课件。恢复在全新Compose项目进行，先校验哈希，再核对业务数据后切换入口。Flyway不降级；旧镜像不兼容新结构时，必须用对应旧备份恢复到新项目。

---

## 八、上线安全检查清单

- [ ] `JWT_SECRET` 是 `openssl rand -base64 48` 生成的新值，不是示例值
- [ ] 数据库与 Redis 口令**不是** `123456`，且端口不对公网开放
- [ ] `.env` / 密钥文件权限为 `600`；Compose 文件由运维账号持有，systemd 的 `/etc/musealgo/oj.env` 由 root 持有
- [ ] 判题机端口未暴露公网
- [ ] **没有**导入 `db-seed.sh` 的演示账号（`admin/123456`）到生产库
- [ ] 同源上传使用显式空值 `OJ_PUBLIC_BASE_URL=`；资源不同源时配置真实资源基础地址，并验证上传文件可访问
- [ ] Nginx 已开启 HTTPS（证书可用 certbot 申请）
- [ ] 已配置数据库、用例、上传、课件的一致备份，并在新项目恢复验收
- [ ] 网关阻断内部接口文档与actuator；监控/告警探针已接入明确接收方
- [ ] 未使用对象存储时，Nginx 的 `/uploads/` 已指向后端的 `OJ_UPLOAD_DIR`，且危险后缀已被拒绝
- [ ] 课件功能若启用，`/coursewares/` 与后端 `OJ_SLIDEV_OUTPUT_DIR` 指向同一份持久卷
