# MuseAlgo-OJ 原生 Linux x86_64 验收包（REL-02，准备态）

> 状态：**blocked-pending-environment（未执行）**。本目录是执行前可复制到目标机的验收包，
> 所有场景必须在目标环境跑完后填写结果；当前不存在任何验收结论。
> 本机为 macOS arm64，**不得**用本机结果冒充原生 x86_64 Linux 结论。

## 目录

| 文件 | 用途 |
|---|---|
| README.md | 本文件：范围、前置、执行顺序、状态图例 |
| 01-runbook-linux-x86_64.md | 逐节操作手册：主机基线→发行物→冷启动→参考数据→首管理员→核心流程→旧库升级→恢复→Judge0→权限/入口→持久化→清理 |
| 02-checklist.md | 逐项验收清单（ID、命令、期望、证据、状态），执行时逐项填写 |
| env.verify.example | 验收环境变量模板（不含任何口令）；复制为私有 env.verify 后填写 |
| smoke-release.sh | 冷启动后的容器内权限、内部入口隔离、持久化冒烟脚本（只读为主，不删数据） |
| synthetic/prepare_synthetic_data.py | 通过真实 API 生成合成管理员/教师/学生/班级/题目/测试用例（随机后缀，可复现） |
| synthetic/judge_matrix.py | 判题矩阵：Java/C/C++/Python 的 AC/WA/CE/TLE/内存边界，记录真实状态码与 runTime/runMemory；凭据从 `--accounts-file`（prepare 脚本产出的私有 accounts.private.json）读取 |

## 前置依赖（缺一即 blocked-pending-environment）

1. **REL-01 发行清单**：干净候选提交 SHA、backend/frontend 镜像 ID 或 digest、迁移锁摘要、JRE/Node 版本。
   构建后生成于 outputs/dev-batch2-2026-10-21/rel01/release-manifest.json（仓库外，不提交）。
2. **QA-02 产物**：管理端真实浏览器回归证据（报告/重判/重放/报名/等级）。本包只交叉引用，不重复实现。
3. **目标主机**：原生 Linux x86_64 + Docker Engine + Compose v2；Judge0 按 docs/DEPLOYMENT.md 第五节独立部署
   （privileged worker、x86_64、不暴露公网）。建议 >=8GB 内存、>=40GB 可用磁盘。
4. 目标机访问方式由用户提供；口令一律在目标机本地输入，不写入对话、仓库或证据。

## 执行顺序

1. 读 01-runbook-linux-x86_64.md，从第 0 节开始逐节执行。
2. 复制 env.verify.example 为私有 env.verify（chmod 600，不入库）并填写本机值。
3. 冷启动后执行 smoke-release.sh（镜像内权限 / 入口隔离 / 持久化冒烟）。
4. 运行 synthetic/prepare_synthetic_data.py 生成合成账号、班级、题目与测试用例。
5. 运行 synthetic/judge_matrix.py 跑判题矩阵（提交接口限流每用户 10 秒 1 次，约 4-6 分钟）。
6. 按 02-checklist.md 逐项填写结果与证据路径；任何一项未通过都不得给出上线通过结论。

## 证据与安全边界

- 证据统一写到目标机私有目录 REL02_EVIDENCE_DIR（默认 ~/musealgo-rel02-evidence，chmod 700），
  完成后整体复制回工作区 outputs/dev-batch2-2026-10-21/rel02/evidence/（不提交）。
- 只允许新建随机名称的 Compose 项目（musealgo-rel02-<随机 token>）；结束后 down -v --remove-orphans，
  并核对容器/卷/网络无残留。不得对接既有业务数据库、生产卷或生产端口。
- 合成数据使用随机后缀，不导入 OJ-Backend/db/seed.sql 演示账号，不在 V63 插默认管理员。
- 合成账号口令只写入证据目录内的 `accounts.private.json`（chmod 600），脚本不回显口令；该文件与证据同等级，不得提交仓库。
- 不 push、不发布镜像、不改远端 CI、不真实外发（邮件/Webhook/外部告警）。
- 本目录本身可提交仓库；证据、清单填写结果、主机信息一律不提交。

## 状态图例

- pending：尚未执行（本包当前默认状态）。
- passed / failed：附命令、退出码、输出摘要与证据文件。
- blocked-pending-environment：缺目标环境或前置产物。
- not-applicable：环境/能力不支持且有依据。
- not verified 不得记作 passed。
