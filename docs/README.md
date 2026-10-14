# 文档索引

本目录收录面向**使用者与贡献者**的公开文档。项目功能概览与快速上手请看根目录 [README](../README.md)。

| 文档 | 内容 | 适合谁 |
|---|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 系统架构：模块划分、依赖约束、判题链路、关键机制 | 想读懂代码、准备二次开发的人 |
| [DEVELOPMENT.md](DEVELOPMENT.md) | 开发指南：环境搭建、工程结构、如何加一个接口 / 迁移 / 前端页面 | 贡献者 |
| [DEPLOYMENT.md](DEPLOYMENT.md) | 生产部署：Docker Compose / systemd 两种方式、反向代理、密钥管理、安全清单 | 自建站点的运维与老师 |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | 常见问题与排错：启动失败、迁移冲突、端口占用、判题机、前端 | 所有人 |
| [COMMENT-GUIDELINES.md](COMMENT-GUIDELINES.md) | 代码注释规范：分层要求、决策型注释写法、已知坑清单 | 提交 PR 的贡献者 |

## 三个入口速查

| 入口 | 地址 |
|---|---|
| 前端 | http://localhost:5173 |
| 后端 API | http://localhost:8080 |
| **接口文档（Knife4j）** | http://localhost:8080/doc.html |
| 健康检查 | http://localhost:8080/actuator/health |
| 指标（Prometheus） | http://localhost:8080/actuator/prometheus |

## 关于文档语言

本项目文档与代码注释以**中文**为主，技术名词保留英文原文。欢迎在不改变语义的前提下修正措辞。
