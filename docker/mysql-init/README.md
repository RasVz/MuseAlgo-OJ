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
> 也不要**单独**改写它——它的 `flyway_schema_history` 必须与迁移文件逐条对应，
> 两者只能成对修改（见[下一节](#修改已合入的迁移文件)）。

## 修改已合入的迁移文件（仅限首次公开发布之前）

Flyway 的 checksum 覆盖迁移文件的**全部字节**——注释、空行、行尾换行都算在内。
所以只改注释也会让已有的库启动失败：

```
Migration checksum mismatch for migration version 52
-> Applied to database : -260103840
-> Resolved locally    : 656283055
```

**默认策略是「不改」**：需要修正时新增一个 `V{n+1}__说明.sql`
（见 [DEVELOPMENT.md](../../docs/DEVELOPMENT.md) 第 3.2 节）。

下面这套「成对修改」的步骤**只适用于首次公开发布之前**——此时还没有任何外部数据库
应用过这些迁移，改完不会影响别人。**仓库一旦公开发布，本节即失效**：
已发布的迁移文件必须保持字节级不变，任何修正都只能通过新增迁移完成。

### 1. Flyway 10 的校验和算法

`ChecksumCalculator` 的实现是：逐行读取（`BufferedReader.readLine()`，**丢弃行尾符**），
每行去掉 BOM 后取 UTF-8 字节喂给 `java.util.zip.CRC32`，**行与行之间不加任何分隔符**，
最后按 32 位有符号整数解释。

Python 复现（`zlib.crc32` 与 Java 的 CRC32 同多项式）：

```python
import zlib
def flyway_checksum(text: str) -> int:
    crc = 0
    for line in text.splitlines():          # 等价于 BufferedReader.readLine()
        crc = zlib.crc32(line.replace('\ufeff', '').encode('utf-8'), crc)
    return crc - 2**32 if crc >= 2**31 else crc
```

> 注意这里**没有**把各行用 `\n` 拼回去。加分隔符算出来的值一定对不上。

### 2. 改动步骤

1. 改 `OJ-backend/oj-admin/src/main/resources/db/migration/V*.sql`；
2. 用上面的函数算出新 checksum，替换 `00-schema.sql` 里 `flyway_schema_history`
   对应记录的值；
3. 已经建过库的环境，同步订正它的历史记录：
   ```sql
   UPDATE flyway_schema_history SET checksum = <新值> WHERE script = 'V52__submission_code_split.sql';
   ```
4. 两步校验都要做：
   - 起一个临时 MySQL（挂载本目录），确认基线能干净导入、且记录值等于新 checksum；
   - 启动后端，日志出现 `Successfully validated N migrations` 与
     `Schema 'oj_db' is up to date. No migration necessary.` 才算通过
     —— 这是 Flyway 亲自复核，比自算结果可信。

> 校验和的一致性是「迁移文件 ↔ `00-schema.sql` ↔ 已建库」三者共同成立的，
> 任何一处漏改都会在下次启动时暴露为 `Migration checksum mismatch`。

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
