package cc.thonly.sqlswitch.sqlite;

import cc.thonly.sqlswitch.model.Column;
import cc.thonly.sqlswitch.model.DataType;
import cc.thonly.sqlswitch.model.Dialect;
import cc.thonly.sqlswitch.model.ForeignKey;
import cc.thonly.sqlswitch.model.Index;
import cc.thonly.sqlswitch.model.IndexColumn;
import cc.thonly.sqlswitch.model.Row;
import cc.thonly.sqlswitch.model.Schema;
import cc.thonly.sqlswitch.model.SourceInfo;
import cc.thonly.sqlswitch.model.Table;
import cc.thonly.sqlswitch.model.Value;
import cc.thonly.sqlswitch.parse.SqlParser;
import cc.thonly.sqlswitch.parse.TokenStream;
import cc.thonly.sqlswitch.parse.Tokenizer;
import cc.thonly.sqlswitch.parse.ValueParser;
import cc.thonly.sqlswitch.util.Log;
import cc.thonly.sqlswitch.util.SqlEscaper;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.sqlite.SQLiteConfig;

/**
 * Reads a SQLite database directly through the SQLite JDBC driver.  The DDL
 * comes from sqlite_master so that AUTOINCREMENT, CHECK constraints and
 * unusual defaults survive exactly as written.
 */
public final class SqliteDatabaseReader {
    private final Path path;

    public SqliteDatabaseReader(Path path) {
        this.path = path;
    }

    public Schema read() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc is not on the classpath", e);
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        Properties props = config.toProperties();
        String url = "jdbc:sqlite:" + path.toAbsolutePath();
        Schema schema;
        try (Connection connection = DriverManager.getConnection(url, props)) {
            SqlParser parser = new SqlParser(Dialect.SQLITE, false);
            SourceInfo source = new SourceInfo(Dialect.SQLITE, path.toString(), true);
            source.detectedBy = "sqlite file header";
            source.characterEncoding = "UTF-8";
            parser.schema().source = source;

            List<TableDdl> tableDdl = new ArrayList<>();
            List<String> indexDdl = new ArrayList<>();
            int skippedObjects = 0;
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT type, name, sql FROM sqlite_master "
                                 + "WHERE name NOT LIKE 'sqlite_%' "
                                 + "AND type IN ('table','index','view','trigger') ORDER BY rowid")) {
                while (rs.next()) {
                    String type = rs.getString(1);
                    String name = rs.getString(2);
                    String sql = rs.getString(3);
                    if (sql == null || sql.isBlank()) {
                        continue;
                    }
                    switch (type) {
                        case "table" -> {
                            if (sql.toUpperCase().startsWith("CREATE VIRTUAL TABLE")) {
                                parser.schema().addSkippedTable(name);
                                parser.schema().addWarning("Virtual table " + name + " was skipped");
                                skippedObjects++;
                            } else {
                                tableDdl.add(new TableDdl(name, sql));
                            }
                        }
                        case "index" -> indexDdl.add(sql);
                        case "view", "trigger" -> {
                            parser.schema().addWarning(type + " " + name
                                    + " is not converted by SQLSwitch");
                            skippedObjects++;
                        }
                        default -> {
                        }
                    }
                }
            }

            for (TableDdl ddl : tableDdl) {
                try {
                    parser.parseCreateTable(ddl.sql(), 0);
                } catch (RuntimeException e) {
                    parser.schema().addWarning("Could not parse table DDL for " + ddl.name()
                            + ", using PRAGMA fallback: " + e.getMessage());
                    try {
                        readTableFallback(connection, parser, ddl);
                    } catch (SQLException fallbackError) {
                        parser.schema().addWarning("PRAGMA fallback failed for " + ddl.name()
                                + ": " + fallbackError.getMessage());
                    }
                }
            }
            for (String ddl : indexDdl) {
                try {
                    parser.parseCreateIndex(ddl, 0);
                } catch (RuntimeException e) {
                    parser.schema().addWarning("Could not parse index DDL: " + e.getMessage());
                }
            }

            readSequences(connection, parser.schema());
            readRows(connection, parser.schema());
            parser.finalizeSchema();
            schema = parser.schema();
            schema.source = source;
            if (skippedObjects > 0) {
                Log.detail("Skipped " + skippedObjects + " view/trigger/virtual-table objects");
            }
        }
        return schema;
    }

    private record TableDdl(String name, String sql) {
    }

    private void readTableFallback(Connection connection, SqlParser parser, TableDdl ddl)
            throws SQLException {
        Table table = new Table(ddl.name());
        table.rawCreateSql = ddl.sql();
        String upperSql = ddl.sql().toUpperCase();
        table.withoutRowid = upperSql.contains("WITHOUT ROWID");
        table.strict = upperSql.contains(" STRICT");

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "PRAGMA table_info(" + SqlEscaper.sqliteIdentifier(ddl.name()) + ")")) {
            while (rs.next()) {
                String name = rs.getString(2);
                String type = rs.getString(3);
                boolean notNull = rs.getInt(4) != 0;
                String defaultSql = rs.getString(5);
                int pk = rs.getInt(6);
                Column column = new Column(name, DataType.parse(type));
                column.nullable = !notNull;
                column.primaryKeyOrder = pk;
                if (defaultSql != null) {
                    column.hasDefault = true;
                    column.defaultValue = parseDefaultValue(defaultSql);
                }
                table.addColumn(column);
            }
        }
        List<Column> pkColumns = table.primaryKeyColumns();
        for (Column column : pkColumns) {
            table.primaryKey.add(new IndexColumn(column.name, null, false));
        }
        if (!table.withoutRowid && pkColumns.size() == 1) {
            Column pk = pkColumns.get(0);
            if (pk.type != null && pk.type.family == cc.thonly.sqlswitch.model.TypeFamily.INTEGER
                    && "INTEGER".equalsIgnoreCase(pk.type.base)) {
                table.rowidAlias = true;
            }
        }

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "PRAGMA index_list(" + SqlEscaper.sqliteIdentifier(ddl.name()) + ")")) {
            while (rs.next()) {
                String indexName = rs.getString(2);
                boolean unique = rs.getInt(3) != 0;
                String origin = rs.getString(4);
                int partial = rs.getInt(5);
                if ("pk".equalsIgnoreCase(origin)) {
                    continue;
                }
                List<IndexColumn> indexColumns = new ArrayList<>();
                try (Statement indexStatement = connection.createStatement();
                     ResultSet info = indexStatement.executeQuery(
                             "PRAGMA index_info(" + SqlEscaper.sqliteIdentifier(indexName) + ")")) {
                    while (info.next()) {
                        String columnName = info.getString(3);
                        if (columnName == null) {
                            indexColumns.add(new IndexColumn("(expression)", null, false));
                        } else {
                            indexColumns.add(IndexColumn.of(columnName));
                        }
                    }
                }
                Index index = new Index(indexName, unique, indexColumns);
                index.origin = unique ? "UNIQUE" : "INDEX";
                if (partial != 0) {
                    index.where = readIndexWhere(connection, indexName);
                }
                table.indexes.add(index);
            }
        }

        java.util.Map<Integer, ForeignKey> foreignKeys = new java.util.LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "PRAGMA foreign_key_list(" + SqlEscaper.sqliteIdentifier(ddl.name()) + ")")) {
            while (rs.next()) {
                int id = rs.getInt(1);
                String refTable = rs.getString(3);
                String from = rs.getString(4);
                String to = rs.getString(5);
                String onUpdate = rs.getString(6);
                String onDelete = rs.getString(7);
                ForeignKey fk = foreignKeys.computeIfAbsent(id, key -> new ForeignKey());
                fk.referencedTable = refTable;
                fk.onUpdate = onUpdate;
                fk.onDelete = onDelete;
                java.util.List<String> columns = new ArrayList<>(fk.columns);
                columns.add(from);
                fk.columns = columns;
                if (to != null) {
                    java.util.List<String> refColumns = new ArrayList<>(fk.referencedColumns);
                    refColumns.add(to);
                    fk.referencedColumns = refColumns;
                }
            }
        }
        table.foreignKeys.addAll(foreignKeys.values());
        parser.schema().addTable(table);
        parser.schema().addWarning("Table " + ddl.name()
                + " was read via PRAGMA fallback; CHECK constraints and some details may be missing");
    }

    private Value parseDefaultValue(String defaultSql) {
        try {
            Tokenizer tokenizer = new Tokenizer(defaultSql, Dialect.SQLITE, false);
            TokenStream tokens = new TokenStream(defaultSql, tokenizer.tokenize());
            return new ValueParser(Dialect.SQLITE).parseDefault(tokens);
        } catch (RuntimeException e) {
            return Value.expression(defaultSql);
        }
    }

    private String readIndexWhere(Connection connection, String indexName) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type='index' AND name=?")) {
            statement.setString(1, indexName);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    String sql = rs.getString(1);
                    if (sql != null) {
                        java.util.regex.Matcher matcher = java.util.regex.Pattern
                                .compile("(?is)\\bWHERE\\b(.*)$").matcher(sql);
                        if (matcher.find()) {
                            return matcher.group(1).trim();
                        }
                    }
                }
            }
        }
        return null;
    }

    private void readSequences(Connection connection, Schema schema) {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT name, seq FROM sqlite_sequence")) {
            while (rs.next()) {
                String name = rs.getString(1);
                long seq = rs.getLong(2);
                if (name != null) {
                    schema.sequences.put(name, seq);
                }
            }
        } catch (SQLException ignored) {
            // sqlite_sequence only exists when at least one AUTOINCREMENT table exists.
        }
    }

    private void readRows(Connection connection, Schema schema) throws SQLException {
        for (Table table : schema.tables) {
            List<Column> writable = table.nonGeneratedColumns();
            if (writable.isEmpty()) {
                continue;
            }
            StringBuilder select = new StringBuilder("SELECT ");
            for (int i = 0; i < writable.size(); i++) {
                if (i > 0) {
                    select.append(", ");
                }
                select.append(SqlEscaper.sqliteIdentifier(writable.get(i).name));
            }
            select.append(" FROM ").append(SqlEscaper.sqliteIdentifier(table.name));
            int count = 0;
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(select.toString())) {
                while (rs.next()) {
                    Value[] values = new Value[table.columns.size()];
                    boolean[] present = new boolean[table.columns.size()];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = Value.NULL;
                    }
                    for (int i = 0; i < writable.size(); i++) {
                        Column column = writable.get(i);
                        Object object = rs.getObject(i + 1);
                        int index = table.columns.indexOf(column);
                        values[index] = Value.fromJava(object);
                        present[index] = true;
                    }
                    table.rows.add(new Row(values, present));
                    count++;
                }
            }
            Log.detail("Read " + count + " rows from " + table.name);
        }
    }
}
