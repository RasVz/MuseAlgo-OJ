# 生产部署指南

> 本文面向**自建站点**的运维者与老师。项目**不是**开箱即用的 SaaS——你需要自己准备服务器、域名与数据库。

## 〇、部署前请先明确三件事

1. **这是教学系统，不是高并发商业 OJ。** 单机部署（2 核 4G 起）足以支撑一个班级到一所学校的规模。
2. **判题机有安全风险。** Judge0 需要 `privileged` 权限执行用户代码，**绝对不能暴露到公网**，详见第四节。
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

### 2.2 编排与启动

仓库根目录自带**可直接使用**的 [`docker-compose.prod.yml`](../docker-compose.prod.yml)（不是示例片段），它编排四个服务：

| 服务 | 镜像 | 端口 | 说明 |
|---|---|---|---|
| `mysql` | `mysql:8.0` | 不映射 | 首次启动自动执行 `docker/mysql-init/00-schema.sql` 建基线 |
| `redis` | `redis:6.0` | 不映射 | AOF 持久化 |
| `backend` | `musealgo-oj/backend:latest` | `127.0.0.1:8080` | 默认只绑本机，供运维探活 |
| `frontend` | `musealgo-oj/frontend:latest` | `80`（`WEB_PORT` 可改） | Nginx 托管静态产物 + `/api` 反代后端 + `/coursewares`、`/uploads` 静态目录 |

前端容器会把 `coursewares-data` 与 `uploads-data` 两个卷**只读**挂到 `/var/www/coursewares`、
`/var/www/uploads`，分别对应后端的课件产物目录与本地上传目录。
这样即使不使用对象存储，用户上传的图片也能通过 `/uploads/<uuid>.<ext>` 被浏览器访问。

```bash
cp .env.example .env
vi .env                 # 至少填 JWT_SECRET 与 OJ_DB_PASS
chmod 600 .env

docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml ps
curl -fsS localhost:8080/actuator/health        # 期望 {"status":"UP"}
```

后端健康检查通过的含义是**完整链路已就绪**：容器 → MySQL 连接 → Flyway 迁移执行完毕 → 应用启动完成。任一环失败，健康检查都不会是 `UP`。

常用运维命令：

```bash
docker compose -f docker-compose.prod.yml logs -f backend     # 看后端日志
docker compose -f docker-compose.prod.yml exec mysql \
  mysql -uroot -p"$OJ_DB_PASS" oj_db -e 'SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;'
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
> 如果你不打算在服务器上编译，也可以在本机或 CI 上执行 `./mvnw -DskipTests package` 与 `npm ci && npm run build`，把 `oj-admin-*.jar` 与 `dist/` 拷到服务器，用任意方式托管（第 3 节的 systemd 方案就是这条路）。

---

## 三、方式 B：systemd 单机 jar（最省资源）

适合「一台服务器、不用容器」的场景。

**1. 准备密钥文件**（仅 root 可读）

```bash
sudo mkdir -p /etc/musealgo
sudo tee /etc/musealgo/oj.env > /dev/null <<'EOF'
JWT_SECRET=<openssl rand -base64 48 生成的随机串>
OJ_DB_HOST=127.0.0.1
OJ_DB_USER=oj
OJ_DB_PASS=<数据库密码>
OJ_REDIS_HOST=127.0.0.1
OJ_TESTCASE_PATH=/opt/musealgo/data/testcases
OJ_UPLOAD_DIR=/opt/musealgo/data/uploads
OJ_SLIDEV_WORKER_DIR=/opt/musealgo/OJ-Slidev
OJ_SLIDEV_OUTPUT_DIR=/opt/musealgo/OJ-Coursewares
DEEPSEEK_API_KEY=<你的大模型 Key，不用 AI 功能可留空>
EOF
sudo chmod 600 /etc/musealgo/oj.env
```

**2. 建服务**

```ini
# /etc/systemd/system/musealgo-oj.service
[Unit]
Description=MuseAlgo-OJ
After=network.target mysqld.service redis.service

[Service]
User=oj
EnvironmentFile=/etc/musealgo/oj.env
ExecStart=/usr/bin/java -Xmx2g -jar /opt/musealgo/oj-admin.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now musealgo-oj
sudo systemctl status musealgo-oj
```

**换密钥时**：只改 `/etc/musealgo/oj.env` 再 `sudo systemctl restart musealgo-oj`，**不需要重新打包**。

**课件功能（可选）**：systemd 方案下课件构建是在宿主机上以外部进程调用 Slidev，
因此宿主机需要安装 **Node ≥ 20**，并在 `OJ_SLIDEV_WORKER_DIR` 指向的工程目录里执行过 `npm ci`。
不需要在线生成课件时无需安装 Node，仅把课件产物目录挂进 Nginx 即可浏览已有课件。

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

> 若后端部署在其他主机，记得把 `OJ_PUBLIC_BASE_URL` 设为对外访问地址，用于生成回调与分享链接。
> 反过来，如果前端 Nginx 已经把 `/api` 与 `/uploads/` 都反代/托管好了（即前后端同源），
> **不要**设置 `OJ_PUBLIC_BASE_URL`——留空时后端返回同源相对地址，换域名、换端口都不会失效。

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
| `OJ_PUBLIC_BASE_URL` | ⭕ | 生成本地上传文件地址用的基础地址。**留空表示同源相对地址**（推荐，前后端同源时不用设）；后端与站点不同源时才填绝对地址 |
| `OJ_RABBITMQ_ENABLED` | ⭕ | 无 broker 时设为 `false`，回退数据库轮询 |
| `OJ_SEARCH_ENABLED` | ⭕ | 无 Meilisearch 时设为 `false`，搜索回退 `LIKE` |

**换 JWT 密钥的后果**：所有已登录用户的 token 立刻失效，需要重新登录。请在低峰期操作。

**换大模型 Key 的流程**：先在平台创建新 key → 改环境变量 → 重启服务 → 冒烟测试（AI 对话能正常回复）→ 回平台**删除旧 key**（这样即使旧 key 曾泄漏也会立即失效）。

---

## 七、升级与回滚

```bash
# 1. 先备份数据库（重要）
docker compose -f docker-compose.prod.yml exec -T mysql \
  mysqldump -uroot -p"$OJ_DB_PASS" --single-transaction oj_db | gzip > backup-$(date +%F).sql.gz

# 2. 换新产物（Docker 方案：重新构建镜像后 up -d，只重建变更的服务）
docker build -f deploy/Dockerfile.backend -t musealgo-oj/backend:latest .
docker compose -f docker-compose.prod.yml up -d

# 3. 观察
curl -fsS localhost:8080/actuator/health   # 应为 {"status":"UP"}
```

- 数据库结构变更由 **Flyway 在启动时自动执行**，无需手工跑 SQL。
- ⚠️ **Flyway 迁移只前进不后退**。回滚旧版本程序**不会**回滚已执行的迁移，因此回滚前请确认旧版本能兼容新表结构（通常是兼容的，因为迁移以新增列/表为主）。
- 升级前务必先备份数据库。

---

## 八、上线安全检查清单

- [ ] `JWT_SECRET` 是 `openssl rand -base64 48` 生成的新值，不是示例值
- [ ] 数据库与 Redis 口令**不是** `123456`，且端口不对公网开放
- [ ] `.env` / 密钥文件权限为 `600`，属主为运行服务的用户
- [ ] 判题机端口未暴露公网
- [ ] **没有**导入 `db-seed.sh` 的演示账号（`admin/123456`）到生产库
- [ ] 已用真实域名配置 `OJ_PUBLIC_BASE_URL`
- [ ] Nginx 已开启 HTTPS（证书可用 certbot 申请）
- [ ] 已配置数据库定期备份
- [ ] `/actuator/prometheus` 只对监控网络开放（或加访问控制）
- [ ] 未使用对象存储时，Nginx 的 `/uploads/` 已指向后端的 `OJ_UPLOAD_DIR`，且危险后缀已被拒绝
- [ ] 课件功能若启用，`/coursewares/` 与后端 `OJ_SLIDEV_OUTPUT_DIR` 指向同一份持久卷
