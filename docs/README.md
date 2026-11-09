# 文档索引

本目录收录项目公开文档。项目功能概览与快速上手请看根目录 [README](../README.md)。

| 文档 | 内容 |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 系统架构：模块划分、依赖约束、判题链路、报告生命周期、关键机制 |
| [DEVELOPMENT.md](DEVELOPMENT.md) | 开发指南：环境搭建、工程结构、数据库迁移、构建测试、代码与注释约定 |
| [DEPLOYMENT.md](DEPLOYMENT.md) | 生产部署：Docker Compose / systemd 两种方式、反向代理、密钥管理、安全清单 |
| [AI-TUTOR.md](AI-TUTOR.md) | 开放式教学、默认诊断、临时样例、题解连通与资源管理 |
| [AI-SERVICES.md](AI-SERVICES.md) | 管理中心维护 AI 接口/模型/密钥、运行模式、主密钥挂载与轮换 |
| [CONTEST-AND-REPORTS.md](CONTEST-AND-REPORTS.md) | 资格快照、正式/全局榜、教师报告操作与边界 |
| [TESTING.md](TESTING.md) | 独立数据库/Judge0、真实 API、浏览器与 MQ 故障验收 |
| [OPERATIONS.md](OPERATIONS.md) | 最小账号、入口隔离、一致备份、恢复及告警 |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | 常见问题与排错：启动失败、迁移冲突、端口占用、判题机、前端 |

## 本地开发入口速查

| 入口 | 地址 |
|---|---|
| 前端 | http://localhost:5173 |
| 后端 API | http://localhost:8080 |
| **接口文档（Knife4j）** | http://localhost:8080/doc.html |
| 健康检查 | http://localhost:8080/actuator/health |
| 指标（Prometheus） | http://localhost:8080/actuator/prometheus |

监控指标要求管理员；生产网关不公开监控与接口文档，见[运维指南](OPERATIONS.md)。

## 关于文档语言

本项目文档与代码注释以**中文**为主，技术名词保留英文原文。欢迎在不改变语义的前提下修正措辞。
