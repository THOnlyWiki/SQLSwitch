package cc.thonly.sqlswitch.write;

import cc.thonly.sqlswitch.infer.ColumnStats;
import cc.thonly.sqlswitch.model.CheckConstraint;
import cc.thonly.sqlswitch.model.Column;
import cc.thonly.sqlswitch.model.ForeignKey;
import cc.thonly.sqlswitch.model.Index;
import cc.thonly.sqlswitch.model.IndexColumn;
import cc.thonly.sqlswitch.model.Schema;
import cc.thonly.sqlswitch.model.Table;
import cc.thonly.sqlswitch.model.TargetConfig;
import cc.thonly.sqlswitch.model.TypeFamily;
import cc.thonly.sqlswitch.model.Value;
import cc.thonly.sqlswitch.util.SqlEscaper;
import cc.thonly.sqlswitch.util.TextUtils;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQLite dump writer.  Identifier quoting, string escaping and transaction
 * handling are deliberately SQLite-native so the result can be piped into the
 * sqlite3 shell (or executed through JDBC) without MySQL compatibility flags.
 */
public final class SqliteWriter extends AbstractSqlWriter {
    private final Map<String, ColumnStats> statsCache = new HashMap<>();
    private final Set<String> usedIndexNames = new HashSet<>();
    private final Set<String> autoincrementTables = new HashSet<>();
    private boolean emittedAutoincrement;

    @Override
    public void write(Schema schema, TargetConfig config, Writer out) throws IOException {
        setup(schema, config, out);
        statsCache.clear();
        usedIndexNames.clear();
        emittedAutoincrement = false;
        autoincrementTables.clear();
        for (Table table : schema.tables) {
            usedIndexNames.add(table.name.toLowerCase(Locale.ROOT));
        }
        writeHeader();
        line("PRAGMA foreign_keys = OFF;");
        if (config.transaction) {
            line("BEGIN TRANSACTION;");
        }
        line("");

        if (config.includeSchema) {
            for (Table table : schema.tables) {
                writeTableLabel(table.name);
                writeTableDdl(table);
            }
        }

        if (config.includeData) {
            for (Table table : schema.tables) {
                if (!table.rows.isEmpty()) {
                    writeDataLabel(table.name, table.rows.size());
                    writeInserts(table);
                }
            }
        }

        if (config.includeSchema) {
            for (Table table : schema.tables) {
                writeIndexes(table);
            }
            writeSequences();
        }

        if (config.transaction) {
            line("COMMIT;");
        }
        line("PRAGMA foreign_keys = ON;");
    }

    private void writeTableDdl(Table table) throws IOException {
        if (table.columns.isEmpty()) {
            schema.addWarning("Table " + table.name + " has no columns and was skipped");
            return;
        }
        if (config.emitDrop) {
            line("DROP TABLE IF EXISTS " + quote(table.name) + ";");
        }
        List<String> definitions = new ArrayList<>();
        List<Column> pkColumns = table.primaryKeyColumns();
        Column auto = table.effectiveAutoIncrementColumn();
        boolean inlineAuto = auto != null && pkColumns.size() == 1
                && pkColumns.get(0) == auto && isIntegerType(auto)
                && !table.withoutRowid && !table.temporary;
        if (inlineAuto) {
            emittedAutoincrement = true;
            if (config.sqliteAutoincrement && auto.autoIncrement) {
                autoincrementTables.add(table.name);
            }
        }
        if (auto != null && table.withoutRowid) {
            schema.addWarning("Table " + table.name
                    + " is WITHOUT ROWID and has an AUTOINCREMENT column; AUTOINCREMENT was dropped");
        } else if (auto != null && !inlineAuto && !isIntegerType(auto)) {
            schema.addWarning("AUTOINCREMENT column " + table.name + "." + auto.name
                    + " is not an integer type; AUTOINCREMENT was dropped");
        } else if (auto != null && pkColumns.size() > 1) {
            schema.addWarning("Composite primary key on " + table.name
                    + " cannot use AUTOINCREMENT in SQLite; it was dropped");
        }

        for (Column column : table.columns) {
            definitions.add(columnDefinition(table, column, auto, inlineAuto));
        }

        if (!inlineAuto && !pkColumns.isEmpty()) {
            StringBuilder pk = new StringBuilder("PRIMARY KEY (");
            for (int i = 0; i < pkColumns.size(); i++) {
                if (i > 0) {
                    pk.append(", ");
                }
                pk.append(quote(pkColumns.get(i).name));
                if (isPrimaryDescending(table, pkColumns.get(i)) && !table.withoutRowid) {
                    pk.append(" DESC");
                }
            }
            pk.append(')');
            definitions.add(pk.toString());
        }

        for (ForeignKey foreignKey : table.foreignKeys) {
            String definition = foreignKeyDefinition(table, foreignKey);
            if (definition != null) {
                definitions.add(definition);
            }
        }
        for (CheckConstraint check : table.checks) {
            if (check.expression == null || check.expression.isBlank()) {
                continue;
            }
            String name = check.name;
            if (name == null || name.isBlank()) {
                name = "chk_" + table.name + "_" + (table.checks.indexOf(check) + 1);
            }
            definitions.add("CONSTRAINT " + quote(name) + " CHECK ("
                    + translateExpression(check.expression) + ")");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(quote(table.name)).append(" (").append(nl);
        for (int i = 0; i < definitions.size(); i++) {
            sb.append("  ").append(definitions.get(i));
            if (i < definitions.size() - 1) {
                sb.append(',');
            }
            sb.append(nl);
        }
        sb.append(')');
        List<String> options = new ArrayList<>();
        if (table.withoutRowid) {
            options.add("WITHOUT ROWID");
        }
        if (table.strict) {
            options.add("STRICT");
        }
        if (!options.isEmpty()) {
            sb.append(' ').append(String.join(", ", options));
        }
        sb.append(';').append(nl);
        out.write(sb.toString());
        if (table.comment != null && !table.comment.isBlank()) {
            schema.addWarning("Table comment on " + table.name + " is not represented in SQLite");
        }
        line("");
    }

    private String columnDefinition(Table table, Column column, Column auto, boolean inlineAuto) {
        ColumnStats stats = stats(table, column);
        StringBuilder sb = new StringBuilder();
        sb.append(quote(column.name)).append(' ');
        String type;
        if (inlineAuto && column == auto) {
            type = "INTEGER";
        } else {
            type = sqliteColumnType(table, column, stats);
        }
        sb.append(type);

        if (inlineAuto && column == auto) {
            sb.append(" PRIMARY KEY");
            if (config.sqliteAutoincrement && column.autoIncrement) {
                sb.append(" AUTOINCREMENT");
            }
            if (column.hasDefault) {
                schema.addWarning("Dropped DEFAULT on AUTOINCREMENT column " + table.name
                        + "." + column.name);
            }
            if (column.comment != null && !column.comment.isBlank()) {
                schema.addWarning("Column comment on " + table.name + "." + column.name
                        + " is not represented in SQLite");
            }
            return sb.toString();
        }

        if (column.generatedExpression != null && !column.generatedExpression.isBlank()) {
            sb.append(" GENERATED ALWAYS AS (").append(translateExpression(column.generatedExpression))
                    .append(") ").append(column.generatedStored ? "STORED" : "VIRTUAL");
        }
        if (!column.nullable || column.isPrimaryKey()) {
            sb.append(" NOT NULL");
        }
        if (column.hasDefault && !column.isGenerated()) {
            String def = defaultClause(table, column);
            if (def != null) {
                sb.append(" DEFAULT ").append(def);
            }
        }
        if (column.type != null && column.type.family == TypeFamily.ENUM
                && !column.type.enumValues.isEmpty()) {
            sb.append(" CHECK (").append(quote(column.name)).append(" IN (");
            for (int i = 0; i < column.type.enumValues.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(SqlEscaper.sqliteStringLiteral(column.type.enumValues.get(i)));
            }
            sb.append("))");
        }
        if (column.collation != null && !column.collation.isBlank()
                && isSqliteCollation(column.collation)) {
            sb.append(" COLLATE ").append(column.collation.toUpperCase(Locale.ROOT));
        }
        if (column.onUpdate != null && !column.onUpdate.isBlank()) {
            schema.addWarning("ON UPDATE on " + table.name + "." + column.name
                    + " is not represented in SQLite");
        }
        if (column.invisible) {
            schema.addWarning("INVISIBLE column " + table.name + "." + column.name
                    + " is not represented in SQLite");
        }
        if (column.comment != null && !column.comment.isBlank()) {
            schema.addWarning("Column comment on " + table.name + "." + column.name
                    + " is not represented in SQLite");
        }
        return sb.toString();
    }

    private String sqliteColumnType(Table table, Column column, ColumnStats stats) {
        String type = TypeMapper.sqliteType(column, table.strict);
        if (type.equals("INTEGER") && stats.maxNumber != null
                && (stats.maxNumber.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0
                || stats.minNumber != null && stats.minNumber.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0)) {
            schema.addWarning("Column " + table.name + "." + column.name
                    + " contains an integer outside SQLite's 64-bit range; mapped to TEXT");
            return "TEXT";
        }
        return type;
    }

    private String defaultClause(Table table, Column column) {
        Value value = column.defaultValue;
        if (value == null) {
            return null;
        }
        return switch (value.kind) {
            case NULL -> "NULL";
            case STRING -> {
                String literal = SqlEscaper.sqliteStringLiteral(value.string);
                yield literal.startsWith("CAST(") ? "(" + literal + ")" : literal;
            }
            case BYTES -> SqlEscaper.sqliteBytesLiteral(value.bytes);
            case NUMBER -> plainNumber(value.number);
            case BOOLEAN -> "1".equals(value.expression) ? "1" : "0";
            case EXPRESSION -> {
                String expr = translateExpression(value.expression).trim();
                String upper = expr.toUpperCase(Locale.ROOT);
                if (upper.startsWith("CURRENT_TIMESTAMP") || upper.equals("NOW()")
                        || upper.equals("CURRENT_DATE") || upper.equals("CURRENT_TIME")
                        || upper.equals("NULL")) {
                    yield switch (upper) {
                        case "NOW()" -> "CURRENT_TIMESTAMP";
                        case "CURRENT_DATE" -> "CURRENT_DATE";
                        case "CURRENT_TIME" -> "CURRENT_TIME";
                        case "NULL" -> "NULL";
                        default -> "CURRENT_TIMESTAMP";
                    };
                }
                if (expr.startsWith("(") && expr.endsWith(")")) {
                    yield expr;
                }
                schema.addWarning("SQLite may not accept expression default '" + TextUtils.truncate(expr, 50)
                        + "' on " + table.name + "." + column.name);
                yield "(" + expr + ")";
            }
            case DEFAULT -> null;
        };
    }

    private String foreignKeyDefinition(Table table, ForeignKey foreignKey) {
        if (foreignKey.columns.isEmpty()) {
            return null;
        }
        Table refTable = schema.getTable(foreignKey.referencedTable);
        if (refTable == null) {
            schema.addWarning("Skipped foreign key on " + table.name + " referencing unknown table "
                    + foreignKey.referencedTable);
            return null;
        }
        List<String> refColumns = foreignKey.referencedColumns;
        if (refColumns == null || refColumns.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Column c : refTable.primaryKeyColumns()) {
                names.add(c.name);
            }
            refColumns = names;
        }
        if (refColumns.isEmpty()) {
            schema.addWarning("Skipped foreign key on " + table.name + " without referenced columns");
            return null;
        }
        String name = foreignKey.name;
        if (name == null || name.isBlank()) {
            name = "fk_" + table.name + "_" + String.join("_", foreignKey.columns);
        }
        StringBuilder sb = new StringBuilder("CONSTRAINT ").append(quote(name))
                .append(" FOREIGN KEY (");
        for (int i = 0; i < foreignKey.columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(quote(foreignKey.columns.get(i)));
        }
        sb.append(") REFERENCES ").append(quote(foreignKey.referencedTable)).append(" (");
        for (int i = 0; i < refColumns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(quote(refColumns.get(i)));
        }
        sb.append(')');
        if (foreignKey.onDelete != null) {
            sb.append(" ON DELETE ").append(foreignKey.onDelete.toUpperCase(Locale.ROOT));
        }
        if (foreignKey.onUpdate != null) {
            sb.append(" ON UPDATE ").append(foreignKey.onUpdate.toUpperCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private void writeIndexes(Table table) throws IOException {
        for (Index index : table.indexes) {
            if (index.isFullText() || index.isSpatial()) {
                schema.addWarning("Skipped " + index.type + " index " + index.name + " on "
                        + table.name + " (SQLite has no such index type)");
                continue;
            }
            if (index.unsupportedReason != null) {
                schema.addWarning("Skipped index on " + table.name + ": " + index.unsupportedReason);
                continue;
            }
            if (index.columns.isEmpty()) {
                continue;
            }
            if (index.unique && sameAsPrimaryKey(table, index)) {
                continue;
            }
            boolean bad = false;
            for (IndexColumn part : index.columns) {
                if (part.column().startsWith("(") || table.getColumn(part.column()) == null) {
                    bad = true;
                    break;
                }
                if (part.prefixLength() != null && index.unique) {
                    schema.addWarning("Unique index " + index.name + " on " + table.name
                            + " uses a prefix; SQLite will index the full column");
                }
            }
            if (bad) {
                schema.addWarning("Skipped unsupported index on " + table.name);
                continue;
            }
            String name = indexName(table, index);
            StringBuilder sb = new StringBuilder();
            sb.append("CREATE ").append(index.unique ? "UNIQUE " : "").append("INDEX ")
                    .append(quote(name)).append(" ON ").append(quote(table.name)).append(" (");
            for (int i = 0; i < index.columns.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                IndexColumn part = index.columns.get(i);
                sb.append(quote(part.column()));
                if (part.descending()) {
                    sb.append(" DESC");
                }
            }
            sb.append(')');
            if (index.where != null && !index.where.isBlank()) {
                sb.append(" WHERE ").append(translateExpression(index.where));
            }
            sb.append(';').append(nl);
            out.write(sb.toString());
        }
    }

    private String indexName(Table table, Index index) {
        String base = index.name;
        if (base == null || base.isBlank()) {
            StringBuilder sb = new StringBuilder(index.unique ? "uq_" : "idx_");
            sb.append(table.name);
            for (IndexColumn part : index.columns) {
                sb.append('_').append(part.column());
            }
            base = sb.toString();
        }
        String name = TextUtils.sanitizeIdentifier(base, 60);
        String candidate = name;
        int suffix = 2;
        while (usedIndexNames.contains(candidate.toLowerCase(Locale.ROOT))) {
            candidate = name + "_" + suffix;
            suffix++;
        }
        usedIndexNames.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }

    private boolean sameAsPrimaryKey(Table table, Index index) {
        List<Column> pk = table.primaryKeyColumns();
        if (pk.size() != index.columns.size()) {
            return false;
        }
        for (int i = 0; i < pk.size(); i++) {
            if (!pk.get(i).name.equalsIgnoreCase(index.columns.get(i).column())) {
                return false;
            }
        }
        return true;
    }

    private void writeSequences() throws IOException {
        if (!config.sqliteAutoincrement || !config.includeSequence || !emittedAutoincrement) {
            return;
        }
        boolean any = false;
        for (Table table : schema.tables) {
            if (autoincrementTables.contains(table.name) && table.autoIncrementValue > 1) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        comment("----------------------------");
        comment("AUTOINCREMENT sequences");
        comment("----------------------------");
        for (Table table : schema.tables) {
            if (!autoincrementTables.contains(table.name) || table.autoIncrementValue <= 1) {
                continue;
            }
            long seq = table.autoIncrementValue - 1;
            String name = SqlEscaper.sqliteStringLiteral(table.name);
            line("INSERT INTO sqlite_sequence (name, seq) SELECT " + name + ", " + seq
                    + " WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = " + name + ");");
            line("UPDATE sqlite_sequence SET seq = MAX(seq, " + seq
                    + ") WHERE name = " + name + ";");
        }
        line("");
    }

    private String translateExpression(String raw) {
        if (raw == null) {
            return "";
        }
        String expr = raw.trim()
                .replaceAll("(?i)_(utf8mb4|utf8mb3|utf8|latin1|binary)\\s*'", "'");
        if (expr.contains("`")) {
            expr = convertBackticksToDoubleQuotes(expr);
        }
        expr = expr.replaceAll("(?i)\\bCOLLATE\\s+utf8mb4_general_ci\\b", "COLLATE NOCASE");
        expr = expr.replaceAll("(?i)\\bCOLLATE\\s+utf8_general_ci\\b", "COLLATE NOCASE");
        expr = expr.replaceAll("(?i)\\bCOLLATE\\s+utf8mb4_bin\\b", "COLLATE BINARY");
        expr = expr.replaceAll("(?i)\\bCOLLATE\\s+utf8_bin\\b", "COLLATE BINARY");
        java.util.regex.Matcher bitMatcher = java.util.regex.Pattern.compile("(?i)\\bB'([01]+)'").matcher(expr);
        expr = bitMatcher.replaceAll(match -> {
            String bits = match.group(1);
            try {
                return new java.math.BigInteger(bits, 2).toString();
            } catch (NumberFormatException e) {
                return match.group();
            }
        });
        return expr;
    }

    private static String convertBackticksToDoubleQuotes(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\'') {
                int end = findStringEnd(input, i, '\'');
                sb.append(input, i, end);
                i = end - 1;
            } else if (c == '`') {
                int end = findStringEnd(input, i, '`');
                String content = input.substring(i + 1, end - 1);
                sb.append('"').append(content.replace("\"", "\"\"")).append('"');
                i = end - 1;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int findStringEnd(String input, int start, char quote) {
        int i = start + 1;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c == '\\' && quote != '\'' && i + 1 < input.length()) {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < input.length() && input.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return input.length();
    }

    private static boolean isSqliteCollation(String collation) {
        String c = collation.toUpperCase(Locale.ROOT);
        return c.equals("BINARY") || c.equals("NOCASE") || c.equals("RTRIM");
    }

    private static boolean isIntegerType(Column column) {
        return column.type != null && column.type.family.isInteger();
    }

    private boolean isPrimaryDescending(Table table, Column column) {
        for (IndexColumn part : table.primaryKey) {
            if (part.column().equalsIgnoreCase(column.name)) {
                return part.descending();
            }
        }
        return false;
    }

    private ColumnStats stats(Table table, Column column) {
        String key = table.name + "\0" + column.name;
        return statsCache.computeIfAbsent(key, k -> ColumnStats.of(table, column));
    }

    @Override
    protected String quote(String identifier) {
        return SqlEscaper.sqliteIdentifier(identifier);
    }

    @Override
    protected String valueLiteral(Value value, Column column, Table table) {
        if (value == null || value.kind == Value.Kind.NULL) {
            return "NULL";
        }
        return switch (value.kind) {
            case STRING -> SqlEscaper.sqliteStringLiteral(value.string);
            case BYTES -> SqlEscaper.sqliteBytesLiteral(value.bytes);
            case NUMBER -> plainNumber(value.number);
            case BOOLEAN -> "1".equals(value.expression) ? "1" : "0";
            case EXPRESSION -> {
                String expr = translateExpression(value.expression);
                String upper = expr.trim().toUpperCase(Locale.ROOT);
                if (upper.startsWith("CURRENT_TIMESTAMP")) {
                    yield "CURRENT_TIMESTAMP";
                }
                if (upper.equals("NOW()")) {
                    yield "CURRENT_TIMESTAMP";
                }
                yield expr;
            }
            case DEFAULT -> {
                schema.addWarning("DEFAULT value inside INSERT was written as NULL for SQLite");
                yield "NULL";
            }
            case NULL -> "NULL";
        };
    }

    @Override
    protected String dialectName() {
        return "SQLite";
    }

    @Override
    protected String emptyRowInsert(Table table) {
        return "INSERT INTO " + quote(table.name) + " DEFAULT VALUES;";
    }
}
