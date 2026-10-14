# 第三方组件与许可说明

MuseAlgo-OJ 自身以 [Apache License 2.0](LICENSE) 发布。项目在构建或运行期依赖以下第三方组件，
其著作权归各自作者所有，遵循各自的许可证。本文件仅列出主要依赖，不是完整的许可证清单，
也不能代替对实际锁定版本、分发物和上游许可文件的逐项核对。发布镜像或其他二进制产物前，
请基于实际构建结果生成依赖清单并保留对应的许可证、NOTICE 和版权信息。

## 运行期服务（非打包，独立部署）

| 组件 | 许可 | 说明 |
|---|---|---|
| [Judge0](https://github.com/judge0/judge0) | **GPL-3.0** | 判题引擎；本项目通过独立服务的 HTTP 接口调用。若另行分发其镜像、源码或修改版本，需单独履行相应许可义务 |
| [MySQL](https://www.mysql.com/) | GPL-2.0（社区版） | 数据库，以独立服务方式使用 |
| [Redis](https://redis.io/legal/licenses/) | BSD-3-Clause（本编排使用 6.0） | 缓存与会话存储，以独立服务方式使用；升级版本时需重新核对许可 |
| [RabbitMQ](https://www.rabbitmq.com/) | MPL-2.0 | 可选消息队列 |
| [Meilisearch](https://github.com/meilisearch/meilisearch) | MIT | 可选全文检索 |
| [Slidev](https://github.com/slidevjs/slidev) | MIT | 课件渲染 |
| [Node.js](https://nodejs.org/) | MIT | 课件构建运行时。官方后端镜像内置 Node 22 与 `OJ-Slidev` 的 npm 生产依赖（各自遵循其许可证） |

## 后端依赖（部分）

| 组件 | 许可 |
|---|---|
| [Spring Boot](https://spring.io/projects/spring-boot) | Apache-2.0 |
| [MyBatis-Plus](https://github.com/baomidou/mybatis-plus) | Apache-2.0 |
| [Flyway](https://flywaydb.org/) | Apache-2.0 |
| [JJWT](https://github.com/jwtk/jjwt) | Apache-2.0 |
| [HikariCP](https://github.com/brettwooldridge/HikariCP) | Apache-2.0 |
| [Knife4j](https://github.com/xiaoymin/knife4j) | Apache-2.0 |

## 前端依赖（部分）

| 组件 | 许可 |
|---|---|
| [Vue.js](https://vuejs.org/) | MIT |
| [Vite](https://vitejs.dev/) | MIT |
| [Element Plus](https://element-plus.org/) | MIT |
| [Pinia](https://pinia.vuejs.org/) | MIT |
| [Vue Router](https://router.vuejs.org/) | MIT |
| [Apache ECharts](https://echarts.apache.org/) | Apache-2.0 |
| [Monaco Editor](https://github.com/microsoft/monaco-editor) | MIT |
| [Axios](https://axios-http.com/) | MIT |
| [Day.js](https://day.js.org/) | MIT |
| [highlight.js](https://highlightjs.org/) | BSD-3-Clause |
| [html2canvas](https://html2canvas.hertzen.com/) | MIT |
| [markdown-it](https://github.com/markdown-it/markdown-it) | MIT |
| [markdown-it-mathjax3](https://github.com/noritama/markdown-it-mathjax3) | MIT |
| [MathJax](https://www.mathjax.org/) | Apache-2.0 |
| [SortableJS](https://sortablejs.github.io/Sortable/) | MIT |
| [github-markdown-css](https://github.com/sindresorhus/github-markdown-css) | MIT |
| [DiceBear](https://www.dicebear.com/) | MIT（核心库；**各头像集合另有限定**，商用前请逐一核对所用 collection 的许可） |

## 需要注意的事项

1. **Judge0 独立部署与分发**：本项目通过 HTTP 调用独立运行的 Judge0，不将其代码链接进后端。
   [GNU GPL FAQ](https://www.gnu.org/licenses/gpl-faq.html#MereAggregation) 说明，是否构成组合程序还取决于通信机制与语义；
   不能仅凭使用 HTTP 对所有部署或分发方式作统一结论。另行分发 Judge0 镜像、源码或修改版本时，应单独核对并履行其许可义务。
2. **演示题目与测试数据**：`OJ-backend/db/seed.sql` 中的演示题面及 `OJ-backend/db/demo-testcases/` 的用例由本项目原创编写，
   随本项目以 Apache-2.0 发布。另行导入的第三方题目与数据须取得相应授权。
3. 若你新增了依赖，请在提交 PR 时同步更新本文件。
