# 开发与运维入口

从仓库根目录执行，先阅读对应指南；不要在有真实数据的环境运行演示数据或集成测试。

| 工具 | 用途 | 文档 |
|---|---|---|
| `run-local.sh` | 本地原生开发启动 | [开发指南](../docs/DEVELOPMENT.md) |
| `db-seed.sh` | 仅开发环境导入原创演示数据与用例 | [README](../README.md#快速开始docker推荐) |
| `gen-demo-testcases.py` | 生成原创演示题用例 | 脚本内说明 |
| `secret-check-pre-commit.sh` | 暂存内容敏感信息检查 | [贡献指南](../CONTRIBUTING.md) |
| `ops.py` | 一致备份、校验、空目标恢复、告警探针 | [运维指南](../docs/OPERATIONS.md) |
| `../tests/run_suite.py` | 独立数据库、真实判题和浏览器验收 | [测试指南](../docs/TESTING.md) |
| `../tests/operations_drill.py` | 独立发行镜像的新装/升级/回滚/恢复演练 | `--help` 与测试指南 |

凭据放受保护的环境文件，生成备份与测试证据放仓库外目录。`ops.py restore` 拒绝已有卷的项目；不要用删除真实卷的方式绕过此保护。
