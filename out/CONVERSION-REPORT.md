# 转换与验证报告

生成日期：2026-10-23  
工具：SQLSwitch 1.0.0  
源文件：

- `wiki.db`：SQLite 数据库（直接只读读取）
- `main.sql`：Navicat 导出的 SQLite SQL

## 数据规模

| 项目 | 数值 |
| --- | --- |
| 业务表 | 44 |
| 数据行 | 7,620 |
| 列 | 404 |
| SQLite 内部表 | `sqlite_sequence`（不导出为业务表） |

`wiki.db` 和 `main.sql` 解析后的表结构、行数据、AUTOINCREMENT 序列完全一致；差异仅在输出文件头部的来源说明。

## 产物

| 文件 | 目标 | 大小 | SHA-256 |
| --- | --- | ---: | --- |
| `wiki.mysql.sql` | MySQL 8.0.29 | 7,076,410 | `d11effad37fca4f6ad9e66eb6e75eac6c230f6bde5e5034fdf1556b04601102d` |
| `wiki.mariadb.sql` | MariaDB 10.1.21 | 7,075,509 | `b9acc3e996ed03657ab1b1acda6d63b60c387123d7a34169be4c743edd838f95` |
| `main.mysql.sql` | MySQL 8.0.29 | 7,076,428 | `58198becf8eab7528cf99e5eee45384db9b6025c0cdbe40dbb1f126b52aa3faf` |
| `main.mariadb.sql` | MariaDB 10.1.21 | 7,075,527 | `297b943d577509f3438bbabf1b74d5d5e5d4785b314ecfdc345964e60137cd80` |

文件编码：UTF-8（无 BOM），换行：LF。

## 端到端验证

### MariaDB 10.1.21（本机 3306）

```text
apply out/wiki.mariadb.sql    -> 44 tables, 159 statements
verify wiki.db                -> VERIFIED: 44 tables, 7620 rows
apply out/main.mariadb.sql    -> 44 tables, 159 statements
verify wiki.db                -> VERIFIED: 44 tables, 7620 rows
```

### MySQL 8.0.29（本机临时实例 3317）

```text
apply out/wiki.mysql.sql      -> 44 tables, 159 statements
verify wiki.db                -> VERIFIED: 44 tables, 7620 rows
apply out/main.mysql.sql      -> 44 tables, 159 statements
verify wiki.db                -> VERIFIED: 44 tables, 7620 rows
```

### 回归 SQLite

把生成的 MySQL/MariaDB dump 再转回 SQLite，并与 `wiki.db` 逐行比较：

```text
MariaDB dump -> SQLite: 44 tables, 7620 rows, 404 columns
MySQL   dump -> SQLite: 44 tables, 7620 rows, 404 columns
逐行差异：0（包含 CRLF、中文、emoji、单引号、分号、HTML/CSS/JavaScript 文本、空串与 NULL）
```

SQLite 回环转换后 `sqlite_sequence` 会额外出现 `thonly_activity_2024/2025/2026` 三行，这是 MySQL `AUTO_INCREMENT` 在 SQLite 中以 `AUTOINCREMENT` 表达的预期结果；原有 wiki 表的序列值与源库一致。

## 导入方式

MySQL：

```bash
mysql -uroot -p wiki_new < out/wiki.mysql.sql
# 或
mysql -uroot -p wiki_new < out/main.mysql.sql
```

MariaDB：

```bash
mariadb -uroot -p wiki_new < out/wiki.mariadb.sql
# 或
mariadb -uroot -p wiki_new < out/main.mariadb.sql
```

导入前请先创建数据库，或使用工具的 `--create-database` / `--database` 选项。

## 说明

- MariaDB 输出针对 MariaDB 10.1 / InnoDB Antelope 的 767 字节索引限制做了兼容：表默认 `utf8`（utf8mb3），仅包含 emoji/4 字节字符的列使用 `utf8mb4`。
- MySQL 输出使用 `utf8mb4` + `ROW_FORMAT=DYNAMIC`。
- 源库中的所有 4 字节字符（`wiki_revision.content`、`wiki_abuse_filter.pattern` 等）均已验证无损。
- SQLite 的 `INTEGER PRIMARY KEY`（即使没有写 `AUTOINCREMENT`）是 rowid 别名，会自动分配 ID；在 MySQL/MariaDB 输出中对应 `AUTO_INCREMENT`。源库 `sqlite_sequence` 中的下一个值也写入了对应的 `AUTO_INCREMENT=`。
- `wiki_gift.icon` 的 emoji 默认值、`wiki_file.license` 的中文默认值、`wiki_user_relation` 的 `FRIEND`/`PENDING` 默认值均已验证。
- 验证使用临时数据库 `sqlswitch_final_*` 和 `sqlswitch_verify_*`，完成后已删除；未改动任何已有业务库。
