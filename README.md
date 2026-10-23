# SQLSwitch

SQLSwitch 是一个用 Java + Gradle 编写的 SQL 导入导出格式转换器，支持：

```text
SQLite  ⇄  MySQL  ⇄  MariaDB
```

它既可以直接读取 SQLite 数据库文件（只读，不修改原库），也可以读取 Navicat、sqlite3、mysqldump、mariadb-dump、phpMyAdmin 等工具导出的 SQL 文件，然后生成目标数据库可直接导入的 SQL。

本仓库最初的两个实验文件：

- `wiki.db`：SQLite 数据库文件
- `main.sql`：Navicat 从该库导出的 SQLite SQL

都已经转换成 MySQL 和 MariaDB 两种格式，放在 `out/` 目录：

| 源文件 | MySQL 输出 | MariaDB 输出 |
| --- | --- | --- |
| `wiki.db` | `out/wiki.mysql.sql` | `out/wiki.mariadb.sql` |
| `main.sql` | `out/main.mysql.sql` | `out/main.mariadb.sql` |

详细的转换与验证记录、SHA-256 见 `out/CONVERSION-REPORT.md` 和 `out/SHA256SUMS.txt`。

四个文件都是 UTF-8、可直接导入的完整 dump。由于 `main.sql` 与 `wiki.db` 是同一份数据的两种导出，转换后的 SQL 除文件头部的来源说明外完全一致。

---

## 1. 构建与运行

环境要求：

- JDK 17 或更高（本项目用 JDK 25 编译、目标字节码 17）
- 首次构建需要联网下载 Gradle 依赖；之后可离线构建

Windows：

```bat
gradlew.bat build
java -jar build\libs\SQLSwitch-1.0.0-all.jar --help
```

也可以在构建后使用包装脚本：

```bat
sqlswitch.bat convert wiki.db --to mysql --to mariadb
```

Linux/macOS：

```sh
./gradlew build
./sqlswitch.sh convert wiki.db --to mysql --to mariadb
```

构建产物：

- `build/libs/SQLSwitch-1.0.0-all.jar`：包含 SQLite/MySQL/MariaDB JDBC 驱动的可执行 fat jar
- `build/libs/SQLSwitch-1.0.0.jar`：普通 jar

运行测试：

```bat
gradlew.bat test
```

---

## 2. 快速开始

自动检测输入格式，把 SQLite 库转换成 MySQL 和 MariaDB：

```bat
java -jar build\libs\SQLSwitch-1.0.0-all.jar convert wiki.db --to mysql --to mariadb
```

把 Navicat 的 SQLite dump 转换成 SQLite 可再次导入的规范 dump：

```bat
java -jar build\libs\SQLSwitch-1.0.0-all.jar convert main.sql --to sqlite -o main.sqlite.sql
```

查看文件格式：

```bat
java -jar build\libs\SQLSwitch-1.0.0-all.jar detect main.sql
```

查看库/文件结构：

```bat
java -jar build\libs\SQLSwitch-1.0.0-all.jar info wiki.db
```

直接导入 MariaDB（可选，默认只生成文件，不会碰任何数据库）：

```bat
set SQLSWITCH_PASSWORD=你的密码
java -jar build\libs\SQLSwitch-1.0.0-all.jar apply out\wiki.mariadb.sql ^
  --type mariadb --host 127.0.0.1 --port 3306 --user root ^
  --database wiki_import_new --create-database
```

校验已导入的库和源文件是否完全一致：

```bat
java -jar build\libs\SQLSwitch-1.0.0-all.jar verify wiki.db ^
  --type mariadb --host 127.0.0.1 --port 3306 --user root ^
  --database wiki_import_new
```

---

## 3. 命令行

```text
sqlswitch convert <input> --to <sqlite|mysql|mariadb> [options]
sqlswitch detect <input> [--json]
sqlswitch info <input> [--from ...] [--json]
sqlswitch apply <dump.sql> --type <mysql|mariadb> --database <name> [options]
sqlswitch verify <source.db|source.sql> --type <mysql|mariadb> --database <name> [options]
```

### convert

| 选项 | 说明 |
| --- | --- |
| `--from auto\|sqlite\|mysql\|mariadb` | 输入格式，默认自动检测 |
| `--to sqlite\|mysql\|mariadb` | 目标格式，可重复，也可逗号分隔或写 `all` |
| `-o, --output <file\|dir>` | 输出文件；多目标时必须是目录 |
| `--stdout` | 输出到标准输出 |
| `--force` | 覆盖已有输出文件 |
| `--charset <name>` | 目标字符集 |
| `--collation <name>` | 目标排序规则 |
| `--target-version <ver>` | 目标服务器版本（MariaDB 默认 `10.1.21`，MySQL 默认 `8.0.29`） |
| `--database <name>` | 在输出中加入 `USE` |
| `--create-database` | 与 `--database` 一起输出 `CREATE DATABASE` |
| `--infer-types <off\|missing\|all>` | 类型推断，默认 `missing` |
| `--infer-dates` / `--infer-booleans` | 额外的类型推断 |
| `--no-widen` | 不自动扩宽过短的 `VARCHAR` |
| `--no-drop` | 不输出 `DROP TABLE IF EXISTS` |
| `--no-schema` / `--no-data` | 只输出数据 / 只输出结构 |
| `--no-comments` / `--no-timestamp` | 精简注释 |
| `--no-transaction` | 不输出事务包裹 |
| `--preserve-order` | 保持输入中的表顺序（默认按表名排序） |
| `--preserve-row-order` | 保持输入中的行顺序（默认按主键排序） |
| `--bom` | 输出 UTF-8 BOM |
| `--max-rows-per-insert <n>` | 每条 INSERT 最大行数，默认 200 |
| `--max-statement-bytes <n>` | 每条 INSERT 字节预算，默认 900000 |
| `--quiet` / `--verbose` | 日志级别 |

### apply / verify

`apply` 会显式创建一个新库（或使用 `--allow-existing` 导入已有库），不会自动修改任何现有数据库。拒绝 `mysql`、`information_schema`、`performance_schema`、`sys` 等系统库。

- `--type mysql|mariadb`
- `--host`、`--port`、`--user`、`--password`（也可用环境变量 `SQLSWITCH_PASSWORD`）
- `--database <name>`：目标库，必需
- `--create-database`：库不存在时创建
- `--allow-existing`：允许导入已存在的库
- `--drop-database --yes`：先删除目标库再重建（危险操作，必须显式确认；同时需要 `--create-database`）

`verify` 会逐表比较源模型与目标库的行数据（行顺序无关），并检查缺表/缺列/多行/少行。

---

## 4. 支持范围与类型映射

### 输入

- SQLite 数据库文件：直接通过 JDBC 只读打开，从 `sqlite_master` 读取原始 DDL，再读取数据。
- SQLite SQL dump：支持 `sqlite_sequence`、`AUTOINCREMENT`、双引号标识符、未加引号的默认值（例如 Navicat 生成的 `DEFAULT 共用领域协议`、`DEFAULT 🎁`）、`PRAGMA`、`UPDATE sqlite_sequence` 等。
- MySQL / MariaDB dump：支持 `mysqldump`、`mariadb-dump`、Navicat、phpMyAdmin 常见格式，包括扩展 INSERT、`AUTO_INCREMENT`、`UNIQUE KEY`、`KEY`、外键、`CHECK`、生成列、字符集/排序规则、`DELIMITER` 等。
- 自动识别 UTF-8 / UTF-16 BOM / GB18030 文本编码，输出统一 UTF-8。

### SQLite → MySQL/MariaDB

| SQLite 声明 | 输出 |
| --- | --- |
| `INTEGER` / `INT` / `BIGINT` | `INT` / `BIGINT`，越界时自动提升 |
| `INTEGER PRIMARY KEY`（rowid 别名） | `INT NOT NULL AUTO_INCREMENT PRIMARY KEY` |
| `INTEGER PRIMARY KEY AUTOINCREMENT` | 同上，并保留下一个 `AUTO_INCREMENT` 值 |
| `VARCHAR(n)` / `CHAR(n)` | `VARCHAR(n)` / `CHAR(n)`，数据超长时自动扩宽 |
| `TEXT` / `CLOB` | `LONGTEXT`（避免 64 KiB 截断） |
| `REAL` / `DOUBLE` | `DOUBLE` |
| `NUMERIC` / `DECIMAL(p,s)` | `DECIMAL(p,s)` |
| `BLOB` | `LONGBLOB` |
| `DATE` / `TIME` / `DATETIME` / `TIMESTAMP` | 同名类型 |
| `BOOLEAN` / `BOOL` | `TINYINT(1)` |
| `JSON` | `LONGTEXT`（保持原文，不强制 JSON 校验） |
| `ENUM` / `SET` | `ENUM` / `SET` |
| 无类型列 | 可用 `--infer-types missing` 根据数据推断 |

SQLite 是动态类型。解析 dump 时，SQLSwitch 会按列亲和性（INTEGER / TEXT / REAL / NUMERIC / BLOB）还原值类型，例如把 `INTEGER` 列里的 `'1'` 还原成整数 `1`，从而让 `wiki.db` 和 `main.sql` 得到完全一致的模型。

### MySQL/MariaDB → SQLite

| MySQL/MariaDB | SQLite |
| --- | --- |
| 所有整数类型 | `INTEGER` |
| `DECIMAL(p,s)` | `DECIMAL(p,s)` / `NUMERIC` |
| `FLOAT` / `DOUBLE` | `REAL` |
| `CHAR` / `VARCHAR` / `TEXT` 系列 | `TEXT` |
| `ENUM` / `SET` / `JSON` | `TEXT`（ENUM 会附带 `CHECK`） |
| `BLOB` / `BINARY` / `VARBINARY` | `BLOB` |
| `DATE` / `TIME` / `DATETIME` / `TIMESTAMP` | 保留同名声明，值按字符串原样保存 |
| `AUTO_INCREMENT` | `INTEGER PRIMARY KEY AUTOINCREMENT` |
| `TINYINT(1)` / `BOOLEAN` | `INTEGER` |

索引、唯一约束、外键、CHECK、生成列、部分索引（SQLite 目标）会尽量保留。

### MariaDB 10.1 兼容

用户环境中的 MariaDB 是 10.1.21，InnoDB 默认 `Antelope` 文件格式、`innodb_large_prefix=0`，索引键上限 767 字节。直接使用 `utf8mb4` 建 `VARCHAR(255)` 唯一索引会失败，因此 MariaDB 输出默认：

- 表默认字符集 `utf8`（utf8mb3），排序规则 `utf8_general_ci`
- 只有确实包含 4 字节字符（emoji 等）的列才单独使用 `CHARACTER SET utf8mb4`
- 不输出 `ROW_FORMAT=DYNAMIC`
- `JSON` 映射成 `LONGTEXT`
- TEXT/BLOB 的默认值在 10.1 不支持时会丢弃并给出警告
- 如果 MariaDB 10.1 的**索引列**需要 utf8mb4 且长度超过 191 字符，会触发 767 字节索引限制；工具会给出警告，建议改用 MariaDB 10.2+ 或缩短索引列。

如果目标是比较新的 MariaDB（10.2+），使用：

```bat
--target-version 10.5
```

会切换到 `utf8mb4` + `ROW_FORMAT=DYNAMIC` 的现代配置。

---

## 5. 验证结果

本仓库的两个实验文件已经做过端到端验证：

- `wiki.db` 直接读取：44 张业务表（不含 `sqlite_sequence`）、7,620 行、404 列
- `main.sql` 解析结果与 `wiki.db` 逐行一致
- `out/wiki.mariadb.sql` 导入 MariaDB 10.1.21，`verify` 结果：44 表 / 7,620 行完全一致
- `out/main.mariadb.sql` 导入 MariaDB 10.1.21，结果一致
- `out/wiki.mysql.sql`、`out/main.mysql.sql` 导入 MySQL 8.0.29，结果一致
- MySQL/MariaDB 输出再转回 SQLite，逐行比较 0 差异（包括 CRLF、中文、emoji、引号、分号、注释样式文本、空串与 NULL）

测试用例见 `src/test/java/cc/thonly/sqlswitch/ExperimentFilesTest.java`，运行 `gradlew.bat test` 可复现。

---

## 6. 已知限制

- 视图、触发器、存储过程、函数、事件暂不转换；遇到时会给出警告，不会静默破坏表数据。
- MySQL 的 `PARTITION BY` 分区定义不转换。
- MySQL 不支持部分索引，转换到 MySQL/MariaDB 时会跳过并在报告中警告；转换到 SQLite 会保留。
- 表达式索引（函数索引）无法安全翻译，会跳过并警告。
- CHECK / 生成列表达式按原样翻译；如果使用了源方言特有的函数，可能需要在目标库手工调整。
- SQLite 把 `TEXT`/`BLOB` 直接用作主键或唯一索引的极端 schema，在 MySQL/MariaDB 中需要索引前缀或改成 `VARCHAR(n)`；遇到时请根据警告调整。
- 表/列注释只在 MySQL/MariaDB 输出中保留；SQLite 不支持注释。
- MySQL `FULLTEXT` / `SPATIAL` 索引在 SQLite 目标中会跳过。
- 极端的厂商私有类型会回退到文本并给出警告。
- `apply` 只支持 MySQL/MariaDB；SQLite 可直接用 `sqlite3` 或任意 SQLite 客户端导入生成的 `.sqlite.sql`。
- 不做“在线数据库同步”；每次转换是源文件/源库到完整目标 dump 的一次性操作。

---

## 7. 项目结构

```text
SQLSwitch/
├── build.gradle                 Gradle 构建
├── settings.gradle
├── gradlew / gradlew.bat        Gradle Wrapper
├── sqlswitch.bat / .sh          可执行包装脚本
├── wiki.db                      实验源：SQLite 数据库
├── main.sql                     实验源：Navicat SQLite dump
├── out/                         已生成的 MySQL/MariaDB dump
└── src/
    ├── main/java/cc/thonly/sqlswitch/
    │   ├── Main.java / Cli.java
    │   ├── core/                转换编排
    │   ├── detect/              格式检测
    │   ├── infer/               类型推断 / 列统计
    │   ├── jdbc/                apply / verify
    │   ├── model/               中立 Schema/Table/Column/Value 模型
    │   ├── parse/               SQL 词法、语句拆分、DDL/INSERT 解析
    │   ├── sqlite/              SQLite 直读
    │   ├── util/
    │   └── write/               MySQL/MariaDB/SQLite 输出
    └── test/java/...            JUnit 5 测试
```

---

## 8. 数据安全

- 读取 SQLite 文件时使用只读连接，不会写回 `wiki.db` 或 `main.sql`。
- `convert`/`detect`/`info` 默认只读写文件，不连接任何数据库。
- `apply` 只有在显式传入 `--database` 时才连接数据库；拒绝系统库。
- `--drop-database` 必须额外传 `--yes`，且只会删除指定名字的库。
- 本项目的测试/验证使用临时库（`sqlswitch_verify_*`），验证完成后已清理，不会改动已有业务库。
