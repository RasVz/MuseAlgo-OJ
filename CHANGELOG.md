# 更新日志

本项目的所有重要变更都会记录在此文件。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 新增

- **演示判题用例**：15 道演示题全部配备真实用例文件（`OJ-backend/db/demo-testcases/`，64 个用例），期望输出由 `scripts/gen-demo-testcases.py` 中的参考实现计算得出（`--check` 可复核），`scripts/db-seed.sh` 负责安装——导入演示数据后即可真实提交判题
- **代码注释规范**：`docs/COMMENT-GUIDELINES.md` 确立分层注释（类头必写、自解释代码不写）与决策型注释写法，并汇总本项目踩过的坑；`CONTRIBUTING.md` 与 `docs/README.md` 挂载入口。全仓注释率后端 1.0% → 24.2%（487 个类类头全覆盖）、前端 224 个文件、22 个 Mapper XML、53 个迁移文件
- **生产编排**：`docker-compose.prod.yml`（MySQL / Redis / 后端发行镜像 / 前端 Nginx 四服务，含健康检查与持久卷、默认不对外暴露数据库端口）
- **环境变量模板**：`.env.example` 补齐 `OJ_DB_PASS` 等数据库连接项
- **界面预览**：README 顶部新增首页截图 `docs/images/home.png`

### 变更

- **CI 由「仅验证编译」改为真正跑测试**：`build.yml` 原先用 `-DskipTests` 把测试整体跳过；实际只有 2 个测试需要 MySQL/Redis，其余 211 个单元测试可在无数据库环境运行。现后端改为 `mvnw -B package`（先测试再打包），前端增加 ESLint 零告警检查，并新增 `integration` profile 供带数据库时跑完整测试

- **生产镜像内置课件运行环境**：后端镜像新增 Node 22 运行时与已 `npm ci` 的 `OJ-Slidev` 工程，并预设 `OJ_SLIDEV_WORKER_DIR` / `OJ_SLIDEV_OUTPUT_DIR`——课件构建依赖「在本容器内执行 slidev CLI」，此前发行镜像并不具备该条件
- **开发栈补齐课件构建能力**：后端开发容器改用 `deploy/Dockerfile.backend` 的 `dev` 阶段（Maven + Node），`OJ-Slidev` 依赖放入命名卷并在首次启动时自动 `npm ci`；前端 Vite 增加 `/uploads` 代理，使本地存储模式下的图片在开发期也能正常显示
- **本地启动脚本**：`scripts/run-local.sh` 改为严格 POSIX `sh` 兼容（原先声明 `/bin/sh` 却使用 Bash 的 `source` 与子串展开），改为「先 `-am -DskipTests install` 再 `spring-boot:run`」两阶段，并且不再把 API Key 片段打印到终端
- **文档一致性**：统一 Flyway 迁移政策（仅「首次公开发布之前」可改历史迁移并同步重导 checksum）、修正 `-Pintegration` 的实际行为说明、补充配置项「留空会覆盖默认值」的陷阱、补齐 `/uploads` 的部署与排错说明
- **品牌统一**：全仓 `AlgoMuse` / `algomuse` 标识统一为 `MuseAlgo` / `musealgo`（容器名、compose 项目名、前端包名、localStorage 键、界面文案、侧边栏品牌字标 `AM` → `MA`）
- **品牌图标重做**：`OJ-frontend/logo.png` 改为 MuseAlgo `MA` 字标（原图案为开源前的历史品牌产物），矢量源随附 `logo.svg`，favicon 引用改为 SVG 优先 + PNG 兜底

### 修复

- **登录后白屏**：登录与注册页面保持单一根节点，使顶层 out-in 路由动画能完成离场并显示目标页面

- **演示难度显示未评定**：将遗留 1/2/3 枚举对齐当前题目评分区间 800/1200/1600
- **首页组件告警**：补齐用户菜单图标导入，修正空态尺寸类型与链接下划线属性

- **首次上传图片无法访问**：上传目录尚不存在时，静态资源位置缺少末尾斜杠；现显式规范为目录 URI，并新增真实 MVC 资源请求回归测试
- **演示数据中文乱码**：演示导入脚本与 SQL 显式使用 utf8mb4，避免 MySQL 客户端默认 latin1 改写中文
- **Apple Silicon 判题兼容**：增加 `JUDGE0_PER_PROCESS_MEMORY_LIMIT`，可为 Rosetta 选择聚合内存限制；默认行为不变，并新增两种模式的 HTTP 请求回归测试

- **`-Pintegration` 实际不执行集成测试**：该 profile 只清空了 Surefire 的 `excludes`，而默认 `includes` 命名规则不含 `*IT`，`LastAdminConcurrencyIT` 从未被选中。现显式声明 includes（默认命名规则 + `**/*IT.java`），已验证该测试会被真正执行
- **生产本地上传缺少访问闭环**：未配置对象存储时后端返回 `/uploads/<uuid>.<ext>`，但生产 Nginx 没有该路由、上传卷也未挂给前端，浏览器拿到的地址打不开。现前端容器只读挂载上传卷并新增 `/uploads/` 路由（拒绝脚本类后缀 + `nosniff`），`OJ_PUBLIC_BASE_URL` 默认留空以返回同源相对地址
- **演示用例只有占位路径**：原 `seed.sql` 的 36 条 `problem_case` 指向 `1/1/input.txt` 之类并不存在的文件，判题必然失败；且部分题目的路径用 `case_order` 而非 `caseId`。现改为 64 条与真实文件一一对应的用例，并统一路径口径
- **演示题面来源不清**：原题面文字取自第三方题库（`source` 标注 `LeetCode n`），不适合随 Apache-2.0 一并分发。现已改写为本项目原创表述，`source` 统一为「演示题」，并补充了输出顺序等确定性约定
- **`.env.example` 的留空赋值会抹掉默认值**：`AI_MODEL=` / `DEEPSEEK_API_URL=` 会让 Spring 收到空字符串从而覆盖 `application.yml` 的默认值；现改为整行注释并补充说明。同时修正「未配置 COS 则上传不可用」的错误描述——实际会降级为本地存储
- **`scripts/db-seed.sh` 无法快速失败**：`docker compose ps` 在守护进程未运行时仍可能返回 0，脚本会空等两分钟；现先检查 `docker info`
- **`.workbuddy-ai/` 未被忽略**：`.gitignore` / `.dockerignore` 只忽略了 `.workbuddy/`，补齐后避免项目记忆与自动化元数据被误入库
- **启动日志重复映射告警**：`PostMapper.selectRecentActivePosts` 同时存在注解定义与 XML 定义，MyBatis 启动时会打印 ERROR 级 `is ignored, because it exists, maybe from xml file`。已移除注解，统一以 XML 为唯一 SQL 来源（运行时行为不变）
- **生产镜像启动即失败**：`SlidevPathProvider` 找不到「同时含 `OJ-backend` / `OJ-Slidev` 的工作区根」时会抛异常中断 Spring 容器初始化，而发行镜像里只有 jar、根本没有这套目录树。现改为退回进程工作目录，并可用 `OJ_SLIDEV_WORKER_DIR` / `OJ_SLIDEV_OUTPUT_DIR` 指定绝对路径
- **课件输出目录属主错误**：`deploy/Dockerfile.backend` 未在镜像内创建 `/app/OJ-Coursewares`，命名卷首次挂载会以 root 属主出现，非 root 进程写不进去
- **部署文档**：`docs/DEPLOYMENT.md` 的编排示例改为指向仓库内可直接使用的 `docker-compose.prod.yml`，并修正容器名相关的运维命令、补齐 `OJ_SLIDEV_*` / `OJ_TESTCASE_PATH` 等路径类变量；`docs/TROUBLESHOOTING.md` 增加课件路径相关条目
- **启动日志降噪**：`logback-spring.xml` 把两组「已知且预期内」的第三方 WARN 降到 ERROR 级别 —— MyBatis-Plus 内建方法被同名 XML 覆盖（`ProblemContestMapper` 的 `insert`/`selectById`/`updateById`/`deleteById` 与 `OutboxEventMapper` 的 `deleteByIds`，属有意重写），以及联合主键实体缺 `@TableId`（`problem_tag` / `user_tag_rating`，全仓只用 `selectList` 与自定义 SQL，不调用 `xxById`）。原因说明见 `docs/TROUBLESHOOTING.md`，运行时行为零变化

## [0.1.0] - 2026-10-13

首次公开发布。

### 新增

- **题库与判题**：题目、题面、测试用例、样例与题解管理；支持 XML / FPS / ZIP 批量导入
- **判题链路**：接入 Judge0 判题机，支持多语言提交、判题任务调度、提交记录与结果详情
- **比赛**：支持 ACM 与 OI 赛制（subtask 部分分）、赛中榜单、赛后自动查重与重测
- **班级教学**：班级创建与学生管理、题单与课件按班下发、作业与进度跟踪
- **课件系统**：基于 Slidev 的课件生成、在线预览与下发
- **AI 教学助手**：接入 OpenAI 兼容接口（默认 DeepSeek），支持流式输出、会话历史与 RAG 检索增强
- **社区**：帖子、评论、点赞、关注，题目与帖子互相关联
- **学情分析**：提交统计、能力雷达、学生画像与家长报告
- **管理后台**：RBAC 权限体系、题目与比赛管理、内容治理、系统配置与提示词模板
- **代码查重**：提交相似度检测与查重报告
- **开发环境**：`compose.dev.yml` 一键启动 MySQL / Redis / 后端 / 前端，判题机与检索组件按 profile 分组
- **工程配套**：Flyway 数据库迁移、密钥防回归扫描（gitleaks + pre-commit）

[Unreleased]: https://github.com/RasVz/MuseAlgo-OJ/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/RasVz/MuseAlgo-OJ/releases/tag/v0.1.0
