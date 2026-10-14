# 数据库初始化目录

本目录会被挂载到 MySQL 容器的 `/docker-entrypoint-initdb.d`。

**它在首次启动且数据卷为空时执行其中的 `.sql` / `.sql.gz` 文件，之后不再执行。**

## `00-schema.sql` —— 结构基线（必须保留）

仓库自带的**表结构基线**：全部 59 张表的结构（**不含任何业务数据**）+ Flyway 的 48 条迁移记录。

**为什么需要它**：本项目的 Flyway 迁移（`V1` 起）是**增量迁移** —— 第一条迁移里的外键就引用了 `` `user` `` 表，而 `user` 表属于迁移之前的既有基线。所以**空库直接启动后端必然失败**，报错：

```
java.sql.SQLException: Failed to open the referenced table 'user'
```

有了本文件，MySQL 首次启动时就会建好全部结构并写入 Flyway 迁移历史；后端启动时 Flyway 校验通过、跳过已应用的迁移，直接进入可用状态。

> ⚠️ **不要删除 `00-schema.sql`**，否则新克隆的库无法启动。
> 它是**纯结构**，不含账号、题目、提交等任何数据，可以安全公开。
> 基线中的 `flyway_schema_history` 必须与所附迁移对应，维护方法见[迁移维护规则](#迁移维护规则)。

## 迁移维护规则

**已执行或已分发给协作者的迁移必须保持原样**，不以仓库是否公开为判断依据。
新增结构变更写成 `V{n+1}__说明.sql`；后端在现有基线上自动执行新迁移。
不必为每次新增迁移重导基线，基线历史未包含的新版本会正常执行。

只有有意更新新安装使用的结构快照时，才从已成功应用迁移、通过 Flyway 校验的临时数据库
重新导出结构和 `flyway_schema_history`，两者必须来自同一个数据库状态。
具体导出示例见 [DEVELOPMENT.md](../../docs/DEVELOPMENT.md#34-新增迁移的正确姿势)。
不得在历史迁移被误改时通过重导快照或手改 checksum 掩盖问题，应先还原历史文件。

Flyway 会按逐行文本计算 checksum，注释和行内容也参与计算；不要把「只改注释」视为无影响。
发现 `Migration checksum mismatch` 时按 [排错指南](../../docs/TROUBLESHOOTING.md#日志出现-flyway-checksum-mismatch) 处理。

## 导入演示数据

结构基线不含业务数据。演示数据（管理员账号、示例题目与班级）在后端启动后用脚本导入：

```bash
./scripts/db-seed.sh
```

## 用自己已有的数据初始化

把你自己的全量备份放进本目录，它会按文件名顺序排在 `00-schema.sql` 之后执行：

```
docker/mysql-init/
├── 00-schema.sql        # 仓库自带的结构基线
└── 01-your-dump.sql.gz  # 你自己的 mysqldump 全量备份
```

> ⚠️ **不要把包含真实用户数据的导出文件提交到 Git。**
> `.gitignore` 已忽略本目录下除 `00-schema.sql` 之外的所有 `*.sql` / `*.sql.gz`。

## 重置数据库

```bash
docker compose -f compose.dev.yml down -v   # 清空数据卷
docker compose -f compose.dev.yml up -d     # 触发重新初始化
```
