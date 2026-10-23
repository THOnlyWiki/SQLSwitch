package cc.thonly.sqlswitch;

import cc.thonly.sqlswitch.core.ConvertRequest;
import cc.thonly.sqlswitch.core.ConversionResult;
import cc.thonly.sqlswitch.core.ConversionService;
import cc.thonly.sqlswitch.detect.DetectionResult;
import cc.thonly.sqlswitch.infer.TypeInference;
import cc.thonly.sqlswitch.jdbc.JdbcApplier;
import cc.thonly.sqlswitch.jdbc.JdbcVerifier;
import cc.thonly.sqlswitch.model.Column;
import cc.thonly.sqlswitch.model.Dialect;
import cc.thonly.sqlswitch.model.Index;
import cc.thonly.sqlswitch.model.Schema;
import cc.thonly.sqlswitch.model.Table;
import cc.thonly.sqlswitch.util.Log;
import cc.thonly.sqlswitch.util.TextUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Command line front end.
 */
public final class Cli {
    private static final Set<String> VALUE_OPTIONS = Set.of(
            "to", "from", "output", "input", "charset", "collation",
            "target-version", "server-version", "database", "infer-types",
            "max-rows-per-insert", "max-statement-bytes",
            "type", "host", "port", "user", "password"
    );
    private static final Set<String> BOOL_OPTIONS = Set.of(
            "help", "version", "quiet", "verbose", "stdout", "force", "bom",
            "no-drop", "no-schema", "no-data", "no-comments", "no-timestamp",
            "no-transaction", "infer-dates", "infer-booleans", "no-widen",
            "create-database", "drop-database", "allow-existing", "yes", "json",
            "infer", "no-infer", "preserve-order", "preserve-row-order"
    );

    private final ConversionService service = new ConversionService();

    public int run(String[] argv) {
        try {
            if (argv.length == 0) {
                printHelp();
                return 0;
            }
            Args args = Args.parse(argv);
            if (args.flag("help") || args.positionals().contains("help")) {
                printHelp();
                return 0;
            }
            if (args.flag("version") || (!args.positionals().isEmpty()
                    && args.positionals().get(0).equalsIgnoreCase("version"))) {
                printVersion();
                return 0;
            }
            if (args.flag("quiet")) {
                Log.setLevel(Log.Level.QUIET);
            } else if (args.flag("verbose")) {
                Log.setLevel(Log.Level.VERBOSE);
            }

            List<String> positionals = new ArrayList<>(args.positionals());
            String command = null;
            if (!positionals.isEmpty()) {
                String first = positionals.get(0).toLowerCase(Locale.ROOT);
                if (Set.of("convert", "detect", "info", "apply", "verify", "help", "version").contains(first)) {
                    command = first;
                    positionals.remove(0);
                }
            }
            if (command == null) {
                command = "convert";
            }
            return switch (command) {
                case "convert" -> runConvert(args, positionals);
                case "detect" -> runDetect(args, positionals);
                case "info" -> runInfo(args, positionals);
                case "apply" -> runApply(args, positionals);
                case "verify" -> runVerify(args, positionals);
                case "help" -> {
                    printHelp();
                    yield 0;
                }
                case "version" -> {
                    printVersion();
                    yield 0;
                }
                default -> {
                    Log.error("Unknown command: " + command);
                    yield 2;
                }
            };
        } catch (IllegalArgumentException e) {
            Log.error(e.getMessage());
            return 2;
        } catch (Exception e) {
            Log.error(e.getMessage() == null ? e.toString() : e.getMessage());
            if (Log.verbose() && e.getCause() != null) {
                e.getCause().printStackTrace(System.err);
            }
            return 2;
        }
    }

    private int runConvert(Args args, List<String> positionals) throws IOException, SQLException {
        String inputValue = args.value("input");
        if (inputValue == null && !positionals.isEmpty()) {
            inputValue = positionals.get(0);
        }
        if (inputValue == null) {
            throw new IllegalArgumentException("convert requires an input file");
        }
        Path input = Path.of(inputValue);
        List<String> targetValues = args.values("to");
        if (targetValues.isEmpty()) {
            throw new IllegalArgumentException("--to is required (sqlite, mysql or mariadb)");
        }
        List<Dialect> targets = new ArrayList<>();
        for (String value : targetValues) {
            for (String part : value.split(",")) {
                if (part.equalsIgnoreCase("all")) {
                    targets.addAll(List.of(Dialect.MYSQL, Dialect.MARIADB, Dialect.SQLITE));
                } else {
                    Dialect d = Dialect.parse(part);
                    if (!targets.contains(d)) {
                        targets.add(d);
                    }
                }
            }
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("No target dialect selected");
        }
        boolean stdout = args.flag("stdout");
        if (stdout && targets.size() > 1) {
            throw new IllegalArgumentException("--stdout can only be used with one --to target");
        }
        String outputValue = args.value("output");
        Path output = outputValue == null ? null : Path.of(outputValue);
        if (output != null && targets.size() > 1 && !Files.isDirectory(output)) {
            throw new IllegalArgumentException("With multiple targets, --output must be an existing directory");
        }

        ConvertRequest base = new ConvertRequest();
        base.input = input;
        base.from = args.value("from") == null || args.value("from").equalsIgnoreCase("auto")
                ? null : Dialect.parse(args.value("from"));
        base.stdout = stdout;
        base.force = args.flag("force");
        base.noDrop = args.flag("no-drop");
        base.noSchema = args.flag("no-schema");
        base.noData = args.flag("no-data");
        base.noComments = args.flag("no-comments");
        base.noTimestamp = args.flag("no-timestamp");
        base.bom = args.flag("bom");
        base.transaction = !args.flag("no-transaction");
        base.preserveOrder = args.flag("preserve-order");
        base.sortRows = !args.flag("preserve-row-order");
        base.inferDates = args.flag("infer-dates");
        base.inferBooleans = args.flag("infer") || args.flag("infer-booleans")
                || "all".equalsIgnoreCase(args.value("infer-types"));
        base.autoWiden = !args.flag("no-widen");
        base.charset = args.value("charset");
        base.collation = args.value("collation");
        base.serverVersion = args.value("server-version") != null
                ? args.value("server-version") : args.value("target-version");
        base.databaseName = args.value("database");
        base.createDatabase = args.flag("create-database");
        if (base.createDatabase && (base.databaseName == null || base.databaseName.isBlank())) {
            throw new IllegalArgumentException("--create-database requires --database NAME");
        }
        String infer = args.value("infer-types");
        if (infer != null) {
            switch (infer.toLowerCase(Locale.ROOT)) {
                case "off", "none" -> base.inferMode = TypeInference.Mode.OFF;
                case "missing" -> base.inferMode = TypeInference.Mode.MISSING;
                case "all" -> base.inferMode = TypeInference.Mode.ALL;
                default -> throw new IllegalArgumentException("--infer-types must be off, missing or all");
            }
        } else if (args.flag("infer")) {
            base.inferMode = TypeInference.Mode.ALL;
        } else if (args.flag("no-infer")) {
            base.inferMode = TypeInference.Mode.OFF;
        }
        if (args.value("max-rows-per-insert") != null) {
            base.maxRowsPerInsert = parsePositiveInt(args.value("max-rows-per-insert"), "--max-rows-per-insert");
        }
        if (args.value("max-statement-bytes") != null) {
            base.maxStatementBytes = parsePositiveInt(args.value("max-statement-bytes"), "--max-statement-bytes");
        }

        int failures = 0;
        if (stdout) {
            Log.infoToStderr(true);
        }
        try {
            for (Dialect target : targets) {
                ConvertRequest request = copy(base);
                request.to = target;
                if (output == null) {
                    request.output = null;
                } else if (targets.size() == 1) {
                    request.output = output;
                } else {
                    String name = input.getFileName().toString();
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) {
                        name = name.substring(0, dot);
                    }
                    request.output = output.resolve(name + "." + target.name().toLowerCase(Locale.ROOT) + ".sql");
                }
                try {
                    ConversionResult result = service.convert(request);
                    Log.info(result.summary());
                    if (!request.stdout && result.warnings() > 0) {
                        int shown = 0;
                        for (String warning : result.messages()) {
                            if (shown++ >= 10) {
                                Log.info("  ... " + (result.messages().size() - 10) + " more warnings");
                                break;
                            }
                            Log.info("  [warn] " + warning);
                        }
                    }
                } catch (Exception e) {
                    failures++;
                    Log.error("Conversion to " + target.displayName() + " failed: " + e.getMessage());
                    if (Log.verbose()) {
                        e.printStackTrace(System.err);
                    }
                }
            }
        } finally {
            if (stdout) {
                Log.infoToStderr(false);
            }
        }
        return failures == 0 ? 0 : 2;
    }

    private int runDetect(Args args, List<String> positionals) throws IOException {
        String inputValue = args.value("input");
        if (inputValue == null && !positionals.isEmpty()) {
            inputValue = positionals.get(0);
        }
        if (inputValue == null) {
            throw new IllegalArgumentException("detect requires an input file");
        }
        DetectionResult result = service.detect(Path.of(inputValue));
        if (args.flag("json")) {
            Log.info("{");
            Log.info("  \"dialect\": \"" + result.dialect().name().toLowerCase(Locale.ROOT) + "\",");
            Log.info("  \"databaseFile\": " + result.databaseFile() + ",");
            Log.info("  \"confidence\": " + String.format(Locale.ROOT, "%.3f", result.confidence()) + ",");
            Log.info("  \"serverVersion\": " + jsonNullable(result.serverVersion()) + ",");
            Log.info("  \"reasons\": [" + jsonArray(result.reasons()) + "]");
            Log.info("}");
        } else {
            Log.info(result.summary());
        }
        return 0;
    }

    private int runInfo(Args args, List<String> positionals) throws Exception {
        String inputValue = args.value("input");
        if (inputValue == null && !positionals.isEmpty()) {
            inputValue = positionals.get(0);
        }
        if (inputValue == null) {
            throw new IllegalArgumentException("info requires an input file");
        }
        Dialect from = args.value("from") == null || args.value("from").equalsIgnoreCase("auto")
                ? null : Dialect.parse(args.value("from"));
        Schema schema = service.loadSchema(Path.of(inputValue), from);
        if (args.flag("json")) {
            printSchemaJson(schema);
            return 0;
        }
        Log.info("Source: " + schema.source.describe());
        Log.info("Tables: " + schema.tables.size() + ", rows: " + schema.totalRows()
                + ", columns: " + schema.totalColumns());
        if (!schema.warnings.isEmpty()) {
            Log.info("Warnings: " + schema.warnings.size());
        }
        for (Table table : schema.tables) {
            Log.info("");
            Log.info("TABLE " + table.name + " (" + table.rows.size() + " rows, "
                    + table.columns.size() + " columns)");
            for (Column column : table.columns) {
                StringBuilder sb = new StringBuilder("  ");
                sb.append(TextUtils.padRight(column.name, 28));
                sb.append(TextUtils.padRight(column.displayType(), 18));
                if (column.isPrimaryKey()) {
                    sb.append(" PK");
                }
                if (column.autoIncrement) {
                    sb.append(" AUTO");
                }
                if (!column.nullable) {
                    sb.append(" NOT NULL");
                }
                if (column.hasDefault) {
                    sb.append(" DEFAULT ").append(column.defaultValue == null
                            ? "NULL" : TextUtils.truncate(column.defaultValue.describe(), 40));
                }
                if (column.generatedExpression != null) {
                    sb.append(" GENERATED");
                }
                Log.info(sb.toString());
            }
            if (!table.indexes.isEmpty()) {
                for (Index index : table.indexes) {
                    Log.info("  INDEX " + (index.unique ? "UNIQUE " : "") + index.name
                            + " (" + index.columns + ")");
                }
            }
        }
        return 0;
    }

    private int runApply(Args args, List<String> positionals) throws Exception {        String inputValue = args.value("input");
        if (inputValue == null && !positionals.isEmpty()) {
            inputValue = positionals.get(0);
        }
        if (inputValue == null) {
            throw new IllegalArgumentException("apply requires a SQL dump file");
        }
        String db = args.value("database");
        if (db == null || db.isBlank()) {
            throw new IllegalArgumentException("apply requires --database NAME");
        }
        Dialect type = args.value("type") == null ? Dialect.MARIADB : Dialect.parse(args.value("type"));
        if (!type.isMySqlFamily()) {
            throw new IllegalArgumentException("--type must be mysql or mariadb");
        }
        boolean drop = args.flag("drop-database");
        if (drop && !args.flag("yes")) {
            throw new IllegalArgumentException("--drop-database is destructive; pass --yes to confirm");
        }
        if (drop && !args.flag("create-database")) {
            throw new IllegalArgumentException("--drop-database requires --create-database (the database is recreated)");
        }
        String password = args.value("password");
        if (password == null) {
            password = System.getenv("SQLSWITCH_PASSWORD");
        }
        if (password == null) {
            java.io.Console console = System.console();
            if (console == null) {
                throw new IllegalArgumentException("No password provided (use --password or SQLSWITCH_PASSWORD)");
            }
            char[] chars = console.readPassword("Password for %s@%s: ", args.value("user") == null
                    ? "root" : args.value("user"), args.value("host") == null ? "127.0.0.1" : args.value("host"));
            password = chars == null ? "" : new String(chars);
        }
        JdbcApplier.Target target = new JdbcApplier.Target();
        target.dialect = type;
        target.host = args.value("host") == null ? "127.0.0.1" : args.value("host");
        target.port = args.value("port") == null ? 3306 : parsePositiveInt(args.value("port"), "--port");
        target.user = args.value("user") == null ? "root" : args.value("user");
        target.password = password;
        target.database = db;
        target.createDatabase = args.flag("create-database");
        target.dropDatabase = drop;
        target.allowExisting = args.flag("allow-existing");
        target.charset = args.value("charset");
        target.collation = args.value("collation");
        JdbcApplier.ApplyResult result = new JdbcApplier().apply(Path.of(inputValue), target);
        Log.info(result.summary());
        return 0;
    }

    private int runVerify(Args args, List<String> positionals) throws Exception {
        String inputValue = args.value("input");
        if (inputValue == null && !positionals.isEmpty()) {
            inputValue = positionals.get(0);
        }
        if (inputValue == null) {
            throw new IllegalArgumentException("verify requires a source file");
        }
        String db = args.value("database");
        if (db == null || db.isBlank()) {
            throw new IllegalArgumentException("verify requires --database NAME");
        }
        Dialect type = args.value("type") == null ? Dialect.MARIADB : Dialect.parse(args.value("type"));
        if (!type.isMySqlFamily()) {
            throw new IllegalArgumentException("--type must be mysql or mariadb");
        }
        String password = args.value("password");
        if (password == null) {
            password = System.getenv("SQLSWITCH_PASSWORD");
        }
        if (password == null) {
            java.io.Console console = System.console();
            if (console == null) {
                throw new IllegalArgumentException("No password provided (use --password or SQLSWITCH_PASSWORD)");
            }
            char[] chars = console.readPassword("Password: ");
            password = chars == null ? "" : new String(chars);
        }
        Dialect from = args.value("from") == null || args.value("from").equalsIgnoreCase("auto")
                ? null : Dialect.parse(args.value("from"));
        Schema source = service.loadSchema(Path.of(inputValue), from);
        JdbcApplier.Target target = new JdbcApplier.Target();
        target.dialect = type;
        target.host = args.value("host") == null ? "127.0.0.1" : args.value("host");
        target.port = args.value("port") == null ? 3306 : parsePositiveInt(args.value("port"), "--port");
        target.user = args.value("user") == null ? "root" : args.value("user");
        target.password = password;
        target.database = db;
        JdbcVerifier.Result result = new JdbcVerifier().verify(source, target);
        Log.info(result.summary());
        for (String note : result.notes()) {
            Log.info("  note: " + note);
        }
        int shown = 0;
        for (String problem : result.problems()) {
            if (shown++ >= 20) {
                Log.info("  ... " + (result.problems().size() - 20) + " more problems");
                break;
            }
            Log.error("  " + problem);
        }
        return result.ok() ? 0 : 1;
    }

    private static ConvertRequest copy(ConvertRequest base) {
        ConvertRequest r = new ConvertRequest();
        r.input = base.input;
        r.output = base.output;
        r.from = base.from;
        r.to = base.to;
        r.stdout = base.stdout;
        r.force = base.force;
        r.noDrop = base.noDrop;
        r.noSchema = base.noSchema;
        r.noData = base.noData;
        r.noComments = base.noComments;
        r.noTimestamp = base.noTimestamp;
        r.bom = base.bom;
        r.transaction = base.transaction;
        r.preserveOrder = base.preserveOrder;
        r.sortRows = base.sortRows;
        r.inferMode = base.inferMode;
        r.inferDates = base.inferDates;
        r.inferBooleans = base.inferBooleans;
        r.autoWiden = base.autoWiden;
        r.charset = base.charset;
        r.collation = base.collation;
        r.serverVersion = base.serverVersion;
        r.databaseName = base.databaseName;
        r.createDatabase = base.createDatabase;
        r.maxRowsPerInsert = base.maxRowsPerInsert;
        r.maxStatementBytes = base.maxStatementBytes;
        return r;
    }

    private static int parsePositiveInt(String value, String option) {
        try {
            int n = Integer.parseInt(value);
            if (n <= 0) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " must be a positive integer");
        }
    }

    private void printSchemaJson(Schema schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"source\": ").append(jsonNullable(schema.source.describe())).append(",\n");
        sb.append("  \"tables\": [\n");
        for (int ti = 0; ti < schema.tables.size(); ti++) {
            Table table = schema.tables.get(ti);
            sb.append("    {\n      \"name\": ").append(jsonNullable(table.name)).append(",\n");
            sb.append("      \"rows\": ").append(table.rows.size()).append(",\n");
            sb.append("      \"columns\": [\n");
            for (int ci = 0; ci < table.columns.size(); ci++) {
                Column column = table.columns.get(ci);
                sb.append("        {\"name\": ").append(jsonNullable(column.name))
                        .append(", \"type\": ").append(jsonNullable(column.displayType()))
                        .append(", \"nullable\": ").append(column.nullable)
                        .append(", \"primaryKey\": ").append(column.isPrimaryKey())
                        .append(", \"autoIncrement\": ").append(column.autoIncrement)
                        .append("}");
                sb.append(ci < table.columns.size() - 1 ? ",\n" : "\n");
            }
            sb.append("      ]\n    }");
            sb.append(ti < schema.tables.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ]\n}");
        Log.info(sb.toString());
    }

    private static String jsonNullable(String value) {
        return value == null ? "null" : "\"" + jsonEscape(value) + "\"";
    }

    private static String jsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(jsonNullable(values.get(i)));
        }
        return sb.toString();
    }

    private static String jsonEscape(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static void printVersion() {
        String version = Cli.class.getPackage().getImplementationVersion();
        Log.info("SQLSwitch " + (version == null || version.isBlank() ? "1.0.0" : version));
    }

    private static void printHelp() {
        Log.info("""
                SQLSwitch 1.0.0 - SQLite / MySQL / MariaDB SQL 转换器

                用法:
                  sqlswitch convert <input> --to <sqlite|mysql|mariadb> [options]
                  sqlswitch detect <input> [--json]
                  sqlswitch info <input> [--from ...] [--json]
                  sqlswitch apply <dump.sql> --type <mysql|mariadb> --database <name> [options]
                  sqlswitch verify <source.db|source.sql> --type <mysql|mariadb> --database <name> [options]

                示例:
                  sqlswitch convert wiki.db --to mysql --to mariadb
                  sqlswitch convert main.sql --to sqlite -o main.sqlite.sql
                  sqlswitch convert wiki.db --to mariadb --target-version 10.1 --database wiki_mariadb
                  sqlswitch detect main.sql
                  sqlswitch info wiki.db

                convert 选项:
                  --from <auto|sqlite|mysql|mariadb>  输入格式 (默认自动检测)
                  --to <sqlite|mysql|mariadb>          目标格式, 可重复/逗号分隔/all
                  -o, --output <file|dir>              输出文件; 多目标时必须是目录
                  --stdout                             输出到标准输出
                  --force                              覆盖已存在的输出文件
                  --charset <name>                     目标字符集 (默认 MySQL=utf8mb4, MariaDB 10.1=utf8)
                  --collation <name>                   目标排序规则
                  --target-version <version>           目标服务器版本 (MariaDB 默认 10.1.21, MySQL 默认 8.0.29)
                  --database <name>                    在输出中加入 USE `name`
                  --create-database                    与 --database 一起使用, 输出 CREATE DATABASE
                  --infer-types <off|missing|all>      类型推断 (默认 missing)
                  --infer-dates / --infer-booleans     额外的类型推断
                  --no-widen                           不自动扩宽过短的 VARCHAR
                  --no-drop                            不输出 DROP TABLE IF EXISTS
                  --no-schema / --no-data              只输出数据 / 只输出结构
                  --no-comments / --no-timestamp       精简文件头
                  --no-transaction                     不输出 BEGIN/START TRANSACTION
                  --preserve-order                     保持输入中的表顺序 (默认按表名排序)
                  --preserve-row-order                 保持输入中的行顺序 (默认按主键排序)
                  --bom                                输出 UTF-8 BOM
                  --max-rows-per-insert <n>            每条 INSERT 的最大行数 (默认 200)
                  --max-statement-bytes <n>            每条 INSERT 的字节预算 (默认 900000)
                  --quiet / --verbose                  日志级别

                apply 选项 (直接导入 MySQL/MariaDB):
                  --type <mysql|mariadb>               服务器类型 (默认 mariadb)
                  --host <host> --port <port>          默认 127.0.0.1:3306
                  --user <user> --password <pass>      默认 root; 密码也可用 SQLSWITCH_PASSWORD
                  --database <name>                    目标数据库 (必需, 拒绝系统库)
                  --create-database                    数据库不存在时创建
                  --drop-database --yes                先删除并重建目标库 (需同时 --create-database)
                  --allow-existing                     允许导入已存在的数据库

                说明:
                  * SQLite 数据库文件 (.db/.sqlite) 会以只读方式直接读取, 不修改原文件。
                  * 默认只生成 SQL 文件; 不会自动导入任何数据库。
                  * MySQL/MariaDB 输出默认使用保守配置, 可导入 MariaDB 10.1 与 MySQL 8。
                """);
    }

    /**
     * Tiny option parser supporting --name=value, --name value, short aliases,
     * repeated options and "--" to end option parsing.
     */
    private static final class Args {
        private final Map<String, List<String>> options = new LinkedHashMap<>();
        private final List<String> positionals = new ArrayList<>();

        static Args parse(String[] argv) {
            Args args = new Args();
            boolean endOptions = false;
            for (int i = 0; i < argv.length; i++) {
                String arg = argv[i];
                if (endOptions) {
                    args.positionals.add(arg);
                    continue;
                }
                if (arg.equals("--")) {
                    endOptions = true;
                    continue;
                }
                if (arg.startsWith("--")) {
                    String nameValue = arg.substring(2);
                    String name;
                    String value = null;
                    int eq = nameValue.indexOf('=');
                    if (eq >= 0) {
                        name = nameValue.substring(0, eq);
                        value = nameValue.substring(eq + 1);
                    } else {
                        name = nameValue;
                    }
                    name = name.toLowerCase(Locale.ROOT);
                    if (BOOL_OPTIONS.contains(name)) {
                        if (value != null) {
                            throw new IllegalArgumentException("--" + name + " does not take a value");
                        }
                        args.options.computeIfAbsent(name, k -> new ArrayList<>()).add("true");
                    } else if (VALUE_OPTIONS.contains(name)) {
                        if (value == null) {
                            if (i + 1 >= argv.length) {
                                throw new IllegalArgumentException("--" + name + " requires a value");
                            }
                            value = argv[++i];
                        }
                        args.options.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
                    } else {
                        throw new IllegalArgumentException("Unknown option: --" + name);
                    }
                } else if (arg.length() == 2 && arg.charAt(0) == '-') {
                    char shortName = arg.charAt(1);
                    String name = switch (shortName) {
                        case 'o' -> "output";
                        case 'i' -> "input";
                        case 'f' -> "force";
                        case 'q' -> "quiet";
                        case 'v' -> "verbose";
                        case 'h' -> "help";
                        default -> null;
                    };
                    if (name == null) {
                        throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                    if (BOOL_OPTIONS.contains(name)) {
                        args.options.computeIfAbsent(name, k -> new ArrayList<>()).add("true");
                    } else {
                        if (i + 1 >= argv.length) {
                            throw new IllegalArgumentException(arg + " requires a value");
                        }
                        args.options.computeIfAbsent(name, k -> new ArrayList<>()).add(argv[++i]);
                    }
                } else {
                    args.positionals.add(arg);
                }
            }
            return args;
        }

        boolean flag(String name) {
            List<String> values = options.get(name);
            return values != null && !values.isEmpty();
        }

        String value(String name) {
            List<String> values = options.get(name);
            return values == null || values.isEmpty() ? null : values.get(values.size() - 1);
        }

        List<String> values(String name) {
            return options.getOrDefault(name, List.of());
        }

        List<String> positionals() {
            return positionals;
        }
    }
}
