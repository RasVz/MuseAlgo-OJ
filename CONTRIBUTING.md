# 贡献指南

感谢你愿意为 MuseAlgo-OJ 出力。本文档说明本项目的开发约定与提交流程。

## 开发环境

推荐直接用 Docker Compose 起全套依赖，避免本地版本差异：

```bash
cp .env.example .env      # 填 JWT_SECRET
docker compose -f compose.dev.yml up -d
```

需要判题时追加 `--profile judge`。若不想用 Docker，或想了解工程结构、数据库迁移机制与常见开发任务，请看 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。

架构与模块依赖约束见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)；遇到问题先查 [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)。

## 分支与提交

- 从 `master` 切出功能分支，命名建议 `feat/简述`、`fix/简述`、`docs/简述`。
- 提交信息使用 **Conventional Commits** 前缀：`feat` / `fix` / `refactor` / `perf` / `docs` / `test` / `chore`。
  示例：`fix(judge): 修正非 <script> 注解中的 XML 转义`
- 一个提交只做一件事，避免把格式化与逻辑改动混在一起。

## 提交前检查

```bash
# 后端：跑单元测试（需要 MySQL/Redis 的集成测试已由 pom.xml 默认排除）
(cd OJ-backend && ./mvnw -q test)

# 前端：代码检查与构建（lint:ci 不带 --fix，与 CI 行为一致）
(cd OJ-frontend && npm run lint:ci && npm run build)
```

> 以上从仓库根目录执行，前端需先安装依赖。本机已初始化 MySQL / Redis 且配置好环境变量时，可用 `(cd OJ-backend && ./mvnw test -Pintegration)` 连同集成测试一起验证。

> 仓库配有 pre-commit 密钥扫描脚本（`scripts/secret-check-pre-commit.sh`）与 CI 端的 gitleaks 工作流。
> 请不要以任何形式提交密钥、口令、Token 或包含真实用户数据的数据库导出。

## 环境变量与密钥

- 所有密钥一律走环境变量，**禁止在代码或配置里写字面量默认值**。
- 本地密钥写入 `.env`（已在 `.gitignore` 中），模板见 `.env.example`。
- 新增配置项时，请同步更新 `.env.example` 与 `application.yml` 的 `${VAR:默认值}` 占位。

## Pull Request

提交 PR 前请确认：

- [ ] 改动聚焦单一主题，描述清楚「为什么」而不只是「做了什么」
- [ ] 后端 `./mvnw test` / 前端 `npm run lint:ci` 通过（CI 会同样执行）
- [ ] 新增或修改了配置项时，`.env.example` 与 README 已同步
- [ ] 没有提交任何密钥、数据库导出、`node_modules`、`target`、`dist` 等构建产物
- [ ] 涉及数据库结构的改动已新增 Flyway 迁移文件（`V{n}__说明.sql`），不修改已有迁移

## 代码风格

- 后端：遵循 Spring / MyBatis-Plus 常规实践，Java 17 语法；包名 `top.everblue.oj.*`。
- 前端：ESLint + Prettier已配置，提交前执行 `npm run lint` 与 `npm run format`。
- 注释与文档使用中文，技术名词保留英文原文。

## 代码注释

完整规范见 **[docs/COMMENT-GUIDELINES.md](docs/COMMENT-GUIDELINES.md)**，核心三条：

1. **分层写**：类头与复杂方法必写，简单getter/setter、纯 CRUD 接口、模板区不写。判断标准是「删掉这行注释，下一个人会不会做出错误的改动」。
2. **写决策不写代码**：重构、绕过框架限制、性能取舍处，必须说明「为什么不用更简单的写法」。这是本项目最看重的注释类型。
3. **注释不得改代码逻辑**：注释补充与格式整理请单独成一个 commit，不要与功能改动混在一起。

## 报告问题

- 功能缺陷与改进建议请走 [Issue](https://github.com/RasVz/MuseAlgo-OJ/issues) 模板。
- 安全漏洞请**不要**开公开 Issue，按 [SECURITY.md](SECURITY.md) 私下报告。
