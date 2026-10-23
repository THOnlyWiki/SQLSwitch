package cc.thonly.sqlswitch.parse;

import cc.thonly.sqlswitch.model.Column;
import cc.thonly.sqlswitch.model.Dialect;
import cc.thonly.sqlswitch.model.Schema;
import cc.thonly.sqlswitch.model.SourceInfo;
import cc.thonly.sqlswitch.model.Table;
import cc.thonly.sqlswitch.model.Value;
import cc.thonly.sqlswitch.util.IoUtils;
import cc.thonly.sqlswitch.util.Log;
import cc.thonly.sqlswitch.util.TextUtils;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Turns a SQL dump into the common {@link Schema} model.
 */
public final class SqlParser {
    private final Dialect dialect;
    private boolean backslashEscapes;
    private final Schema schema = new Schema();
    private final ValueParser valueParser;
    private final CreateTableParser createTableParser;
    private final InsertParser insertParser;
    private final IndexParser indexParser;

    public SqlParser(Dialect dialect) {
        this(dialect, dialect == null || dialect.isMySqlFamily());
    }

    public SqlParser(Dialect dialect, boolean backslashEscapes) {
        this.dialect = dialect;
        this.backslashEscapes = backslashEscapes;
        this.valueParser = new ValueParser(dialect);
        this.createTableParser = new CreateTableParser(dialect, valueParser, schema);
        this.insertParser = new InsertParser(schema, valueParser, createTableParser, dialect);
        this.indexParser = new IndexParser(schema, createTableParser);
        this.schema.name = null;
    }

    public Schema schema() {
        return schema;
    }

    public Schema parseFile(Path path) throws IOException {
        boolean escapes = backslashEscapes || dialect == Dialect.MYSQL || dialect == Dialect.MARIADB;
        if (dialect.isMySqlFamily()) {
            String head = readHead(path, 128 * 1024);
            if (head.toUpperCase(Locale.ROOT).contains("NO_BACKSLASH_ESCAPES")
                    && !head.toUpperCase(Locale.ROOT).contains("REPLACE(@@SQL_MODE")) {
                escapes = false;
            }
        }
        this.backslashEscapes = escapes;
        SourceInfo source = new SourceInfo(dialect, path.toString(), false);
        source.detectedBy = "file";
        source.characterEncoding = IoUtils.detectCharset(path).encodingName();
        schema.source = source;
        try (Reader reader = IoUtils.openReader(path)) {
            StatementSplitter splitter = new StatementSplitter(reader, dialect, escapes, 256 * 1024 * 1024);
            List<StatementSplitter.SqlStatement> statements = splitter.splitAll();
            Log.detail("Parsed " + statements.size() + " SQL statements");
            for (StatementSplitter.SqlStatement statement : statements) {
                try {
                    parseStatement(statement.text(), statement.line());
                } catch (ParseException e) {
                    String[] stmtLines = statement.text().split("\n", -1);
                    String context = "";
                    int relLine = e.line() > 0 ? e.line() : 1;
                    if (relLine <= stmtLines.length) {
                        String l = stmtLines[relLine - 1];
                        int col = Math.max(1, e.column());
                        int from = Math.max(0, col - 30);
                        int to = Math.min(l.length(), col + 50);
                        context = " | rel-line " + relLine + " chars " + from + "-" + to + ": "
                                + l.substring(from, to);
                    }
                    throw new ParseException("at file line " + statement.line() + ": " + e.getMessage()
                            + " | statement: " + TextUtils.truncate(
                            statement.text().replace('\n', ' ').replace('\r', ' '), 240) + context,
                            statement.line(), 1);
                }
            }
        }
        finalizeSchema();
        return schema;
    }

    public Schema parseText(String sql) {
        if (dialect.isMySqlFamily()) {
            String upper = sql.toUpperCase(Locale.ROOT);
            if (upper.contains("NO_BACKSLASH_ESCAPES") && !upper.contains("REPLACE(@@SQL_MODE")) {
                backslashEscapes = false;
            }
        }
        SourceInfo source = new SourceInfo(dialect, "(string)", false);
        source.detectedBy = "string";
        schema.source = source;
        StatementSplitter splitter = new StatementSplitter(new StringReader(sql), dialect, backslashEscapes,
                256 * 1024 * 1024);
        try {
            for (StatementSplitter.SqlStatement statement : splitter.splitAll()) {
                parseStatement(statement.text(), statement.line());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        finalizeSchema();
        return schema;
    }

    private static String readHead(Path path, int max) throws IOException {
        byte[] bytes = IoUtils.firstBytes(path, max);
        IoUtils.DetectedText detected = IoUtils.detectCharset(path);
        return IoUtils.decode(bytes, detected.charset());
    }

    public void parseStatement(String sql, int line) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        List<Token> tokens = new Tokenizer(sql, dialect, backslashEscapes).tokenize();
        if (tokens.isEmpty() || tokens.get(0).isEof()) {
            return;
        }
        TokenStream ts = new TokenStream(sql, tokens);
        Token first = ts.peek();
        String upper = first.upper();
        switch (upper) {
            case "CREATE" -> parseCreate(ts, line);
            case "INSERT" -> {
                ts.next();
                insertParser.parse(ts, line, false);
            }
            case "REPLACE" -> {
                ts.next();
                insertParser.parse(ts, line, true);
            }
            case "ALTER" -> parseAlter(ts, line);
            case "UPDATE" -> parseUpdate(ts, line);
            case "DELETE" -> parseDelete(ts, line);
            case "DROP" -> {
                // DROP statements in dumps are bookkeeping; the writer emits
                // its own DROP TABLE IF EXISTS based on the model.
            }
            case "USE" -> {
                ts.next();
                if (ts.peek().isIdentifier()) {
                    schema.name = TextUtils.stripSchema(ts.next().text);
                }
            }
            case "SET", "PRAGMA", "BEGIN", "START", "COMMIT", "ROLLBACK", "LOCK", "UNLOCK",
                    "ANALYZE", "OPTIMIZE", "FLUSH", "GRANT", "REVOKE", "DELIMITER", "SHOW",
                    "DESCRIBE", "DESC", "TRUNCATE" -> {
                // Session and maintenance statements do not belong in the model.
            }
            case "WITH" -> schema.addWarning("WITH/CTE statement at line " + line + " was skipped");
            default -> schema.addWarning("Ignoring unsupported statement at line " + line + ": "
                    + TextUtils.truncate(sql, 100));
        }
    }

    private void parseCreate(TokenStream ts, int line) {
        ts.expectKeyword("CREATE");
        ts.matchKeyword("OR");
        ts.matchKeyword("REPLACE");
        ts.matchKeyword("TEMPORARY");
        if (ts.matchKeyword("VIRTUAL")) {
            ts.matchKeyword("TABLE");
            if (ts.matchKeyword("IF")) {
                ts.matchKeyword("NOT");
                ts.matchKeyword("EXISTS");
            }
            if (ts.peek().isIdentifier()) {
                schema.addSkippedTable(TextUtils.stripSchema(ts.next().text));
            }
            schema.addWarning("Virtual table at line " + line
                    + " was skipped; virtual tables have no portable SQL representation");
            return;
        }
        if (ts.matchKeyword("TABLE")) {
            createTableParser.parse(ts, line);
            return;
        }
        boolean unique = ts.matchKeyword("UNIQUE");
        String type = null;
        if (ts.matchKeyword("FULLTEXT")) {
            type = "FULLTEXT";
        } else if (ts.matchKeyword("SPATIAL")) {
            type = "SPATIAL";
        }
        if (ts.matchKeyword("INDEX")) {
            indexParser.parse(ts, line, unique, type);
            return;
        }
        if (ts.matchKeyword("DATABASE") || ts.matchKeyword("SCHEMA")) {
            if (ts.matchKeyword("IF")) {
                ts.matchKeyword("NOT");
                ts.matchKeyword("EXISTS");
            }
            if (ts.peek().isIdentifier()) {
                schema.name = TextUtils.stripSchema(ts.next().text);
            }
            return;
        }
        if (ts.peek().isKeyword("VIEW") || ts.peek().isKeyword("TRIGGER")
                || ts.peek().isKeyword("PROCEDURE") || ts.peek().isKeyword("FUNCTION")
                || ts.peek().isKeyword("EVENT") || ts.peek().isKeyword("DEFINER")
                || ts.peek().isKeyword("ALGORITHM") || ts.peek().isKeyword("SQL")) {
            schema.addWarning("Views/triggers/routines are not converted; skipped a CREATE statement at line "
                    + line);
            return;
        }
        if (ts.peek().isKeyword("USER") || ts.peek().isKeyword("ROLE")) {
            return;
        }
        schema.addWarning("Unsupported CREATE statement at line " + line + ": "
                + TextUtils.truncate(ts.sql(), 100));
    }

    private void parseAlter(TokenStream ts, int line) {
        ts.expectKeyword("ALTER");
        ts.matchKeyword("IGNORE");
        ts.matchKeyword("ONLINE");
        if (!ts.matchKeyword("TABLE")) {
            schema.addWarning("Unsupported ALTER statement at line " + line);
            return;
        }
        String tableName = TextUtils.stripSchema(ts.readQualifiedIdentifier());
        Table table = schema.getTable(tableName);
        if (table == null) {
            schema.addWarning("ALTER TABLE references unknown table " + tableName + " (line " + line + ")");
            return;
        }
        int end = ts.tokens().size() - 1;
        while (!ts.atEnd()) {
            if (ts.atSymbol(";")) {
                break;
            }
            if (ts.matchKeyword("AUTO_INCREMENT")) {
                ts.matchSymbol("=");
                if (ts.peek().kind == Token.Kind.NUMBER) {
                    table.autoIncrementValue = Long.parseLong(ts.next().text);
                }
            } else if (ts.matchKeyword("ENGINE")) {
                ts.matchSymbol("=");
                if (ts.peek().isIdentifier()) {
                    table.engine = ts.next().text;
                }
            } else if (ts.matchKeyword("DEFAULT")) {
                if (ts.matchKeyword("CHARSET")) {
                    ts.matchSymbol("=");
                    if (ts.peek().isIdentifier()) {
                        table.charset = ts.next().text;
                    }
                } else if (ts.matchKeyword("CHARACTER")) {
                    ts.matchKeyword("SET");
                    ts.matchSymbol("=");
                    if (ts.peek().isIdentifier()) {
                        table.charset = ts.next().text;
                    }
                } else if (ts.matchKeyword("COLLATE")) {
                    ts.matchSymbol("=");
                    if (ts.peek().isIdentifier()) {
                        table.collation = ts.next().text;
                    }
                } else {
                    ts.next();
                }
            } else if (ts.matchKeyword("CONVERT")) {
                ts.matchKeyword("TO");
                ts.matchKeyword("CHARACTER");
                ts.matchKeyword("SET");
                if (ts.peek().isIdentifier()) {
                    table.charset = ts.next().text;
                }
            } else if (ts.matchKeyword("ROW_FORMAT")) {
                ts.matchSymbol("=");
                if (ts.peek().isIdentifier()) {
                    table.rowFormat = ts.next().text;
                }
            } else if (ts.matchKeyword("ADD")) {
                String constraintName = null;
                if (ts.matchKeyword("CONSTRAINT")) {
                    if (ts.peek().isIdentifier() && !isConstraintHead(ts.peek())) {
                        constraintName = ts.next().text;
                    }
                }
                if (isConstraintHead(ts.peek())) {
                    createTableParser.parseTableConstraint(ts, table, end, constraintName);
                } else {
                    schema.addWarning("ALTER TABLE " + tableName
                            + " ADD COLUMN/MODIFY is not converted (line " + line + ")");
                    break;
                }
            } else if (ts.matchKeyword("DISABLE") || ts.matchKeyword("ENABLE")) {
                ts.matchKeyword("KEYS");
            } else if (ts.matchKeyword("ALGORITHM") || ts.matchKeyword("LOCK")) {
                ts.matchSymbol("=");
                if (!ts.atEnd()) {
                    ts.next();
                }
            } else {
                schema.addWarning("Ignoring unsupported ALTER TABLE clause for " + tableName
                        + " at line " + line + ": " + TextUtils.truncate(ts.peek().raw, 60));
                break;
            }
        }
    }

    private static boolean isConstraintHead(Token token) {
        return switch (token.upper()) {
            case "PRIMARY", "UNIQUE", "KEY", "INDEX", "FULLTEXT", "SPATIAL", "FOREIGN", "CHECK" -> true;
            default -> false;
        };
    }

    private void parseUpdate(TokenStream ts, int line) {
        ts.expectKeyword("UPDATE");
        if (ts.peek().isIdentifier()) {
            String table = TextUtils.stripSchema(ts.next().text);
            if (table.equalsIgnoreCase("sqlite_sequence")) {
                parseSequenceUpdate(ts);
                return;
            }
        }
        schema.addWarning("Ignoring UPDATE statement at line " + line);
    }

    private void parseSequenceUpdate(TokenStream ts) {
        if (!ts.matchKeyword("SET")) {
            return;
        }
        if (ts.peek().isIdentifier() && ts.peek().text.equalsIgnoreCase("seq")) {
            ts.next();
            ts.matchSymbol("=");
            if (ts.peek().kind == Token.Kind.NUMBER) {
                long seq = Long.parseLong(ts.next().text);
                if (ts.matchKeyword("WHERE")) {
                    if (ts.peek().isIdentifier()) {
                        ts.next();
                        ts.matchSymbol("=");
                        if (ts.peek().kind == Token.Kind.STRING) {
                            schema.sequences.put(ts.next().text, seq);
                        }
                    }
                }
            }
        }
    }

    private void parseDelete(TokenStream ts, int line) {
        ts.expectKeyword("DELETE");
        ts.matchKeyword("FROM");
        if (ts.peek().isIdentifier()) {
            String table = TextUtils.stripSchema(ts.next().text);
            if (table.equalsIgnoreCase("sqlite_sequence")) {
                schema.sequences.clear();
                return;
            }
        }
        schema.addWarning("Ignoring DELETE statement at line " + line);
    }

    /**
     * Parses a single CREATE TABLE statement and adds it to the current schema.
     * Used by the SQLite database reader, which obtains DDL from sqlite_master.
     */
    public Table parseCreateTable(String sql, int line) {
        List<Token> tokens = new Tokenizer(sql, dialect, backslashEscapes).tokenize();
        TokenStream ts = new TokenStream(sql, tokens);
        ts.expectKeyword("CREATE");
        ts.matchKeyword("TEMPORARY");
        ts.expectKeyword("TABLE");
        return createTableParser.parse(ts, line);
    }

    public void parseCreateIndex(String sql, int line) {
        List<Token> tokens = new Tokenizer(sql, dialect, backslashEscapes).tokenize();
        TokenStream ts = new TokenStream(sql, tokens);
        ts.expectKeyword("CREATE");
        boolean unique = ts.matchKeyword("UNIQUE");
        String type = null;
        if (ts.matchKeyword("FULLTEXT")) {
            type = "FULLTEXT";
        } else if (ts.matchKeyword("SPATIAL")) {
            type = "SPATIAL";
        }
        ts.expectKeyword("INDEX");
        indexParser.parse(ts, line, unique, type);
    }

    public void finalizeSchema() {
        for (var entry : schema.sequences.entrySet()) {
            Table table = schema.getTable(entry.getKey());
            if (table == null) {
                continue;
            }
            Column pk = table.singlePrimaryKeyColumn();
            if (pk != null && pk.type != null && pk.type.family.isInteger()) {
                pk.autoIncrement = true;
            }
            long seq = entry.getValue();
            table.autoIncrementValue = Math.max(table.autoIncrementValue, seq + 1);
        }
        for (Table table : schema.tables) {
            Column auto = table.effectiveAutoIncrementColumn();
            if (auto == null) {
                continue;
            }
            long max = 0;
            int idx = table.columns.indexOf(auto);
            for (var row : table.rows) {
                Value v = row.values[idx];
                if (v != null && v.kind == Value.Kind.NUMBER && v.number != null) {
                    max = Math.max(max, v.number.longValue());
                }
            }
            if (max + 1 > table.autoIncrementValue) {
                table.autoIncrementValue = max + 1;
            }
        }
    }
}
