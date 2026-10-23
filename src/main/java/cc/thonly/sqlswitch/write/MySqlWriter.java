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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MySQL/MariaDB dump writer.  MariaDB-specific compatibility switches are
 * driven by {@link TargetConfig#isLegacyMariaDb()}.
 */
public class MySqlWriter extends AbstractSqlWriter {
    private final Map<String, ColumnStats> statsCache = new HashMap<>();

    @Override
    public void write(Schema schema, TargetConfig config, Writer out) throws IOException {
        setup(schema, config, out);
        statsCache.clear();
        writeHeader();
        writePreamble();

        if (config.includeSchema) {
            for (Table table : schema.tables) {
                writeTableLabel(table.name);
                writeTableDdl(table);
            }
        }

        boolean hasRows = schema.totalRows() > 0 && config.includeData;
        if (hasRows && config.transaction) {
            line("START TRANSACTION;");
            line("");
        }
        if (config.includeData) {
            for (Table table : schema.tables) {
                if (!table.rows.isEmpty()) {
                    writeDataLabel(table.name, table.rows.size());
                    writeInserts(table);
                }
            }
        }
        if (hasRows && config.transaction) {
            line("COMMIT;");
        }
        writePostamble();
    }

    protected void writePreamble() throws IOException {
        line("SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT;");
        line("SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS;");
        line("SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION;");
        line("SET NAMES utf8mb4;");
        line("SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0;");
        line("SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE=REPLACE(@@SQL_MODE,'NO_BACKSLASH_ESCAPES','');");
        line("");
        if (config.emitCreateDatabase && config.databaseName != null && !config.databaseName.isBlank()) {
            line("CREATE DATABASE IF NOT EXISTS " + quote(config.databaseName)
                    + " DEFAULT CHARACTER SET " + config.effectiveCharset()
                    + " COLLATE " + config.effectiveCollation() + ";");
            line("USE " + quote(config.databaseName) + ";");
            line("");
        } else if (config.databaseName != null && !config.databaseName.isBlank()) {
            line("USE " + quote(config.databaseName) + ";");
            line("");
        }
    }

    protected void writePostamble() throws IOException {
        line("");
        line("SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS;");
        line("SET SQL_MODE=@OLD_SQL_MODE;");
        line("SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT;");
        line("SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS;");
        line("SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION;");
    }

    protected void writeTableDdl(Table table) throws IOException {
        if (table.columns.isEmpty()) {
            schema.addWarning("Table " + table.name + " has no columns and was skipped");
            return;
        }
        if (config.emitDrop) {
            line("DROP TABLE IF EXISTS " + quote(table.name) + ";");
        }
        List<String> definitions = new ArrayList<>();
        Set<String> usedIndexNames = new HashSet<>();
        Set<String> usedForeignKeyNames = new HashSet<>();
        Set<String> usedCheckNames = new HashSet<>();

        for (Column column : table.columns) {
            definitions.add(columnDefinition(table, column));
        }

        List<Column> pkColumns = table.primaryKeyColumns();
        if (!pkColumns.isEmpty()) {
            StringBuilder pk = new StringBuilder("PRIMARY KEY (");
            List<IndexColumn> pkParts = primaryKeyParts(table);
            for (int i = 0; i < pkParts.size(); i++) {
                if (i > 0) {
                    pk.append(", ");
                }
                IndexColumn part = pkParts.get(i);
                pk.append(quote(part.column()));
                if (part.descending() && !config.isLegacyMariaDb()) {
                    pk.append(" DESC");
                }
                Column pkColumn = table.getColumn(part.column());
                if (pkColumn != null && pkColumn.type != null
                        && (pkColumn.type.family == TypeFamily.TEXT
                        || pkColumn.type.family == TypeFamily.BLOB)) {
                    schema.addWarning("PRIMARY KEY column " + table.name + "." + pkColumn.name
                            + " is TEXT/BLOB; MySQL/MariaDB requires a prefix or VARCHAR conversion");
                }
            }
            pk.append(')');
            definitions.add(pk.toString());
        } else if (table.hasEffectiveAutoIncrement()) {
            schema.addWarning("AUTO_INCREMENT column in " + table.name + " has no primary key");
        }

        for (Index index : table.indexes) {
            String definition = indexDefinition(table, index, usedIndexNames);
            if (definition != null) {
                definitions.add(definition);
            }
        }

        for (ForeignKey foreignKey : table.foreignKeys) {
            String definition = foreignKeyDefinition(table, foreignKey, usedForeignKeyNames);
            if (definition != null) {
                definitions.add(definition);
            }
        }

        if (config.emitCheckConstraints) {
            for (CheckConstraint check : table.checks) {
                if (check.expression == null || check.expression.isBlank()) {
                    continue;
                }
                String name = check.name;
                if (name == null || name.isBlank()) {
                    name = uniqueName("chk_" + table.name + "_" + (usedCheckNames.size() + 1), usedCheckNames);
                } else {
                    name = uniqueName(name, usedCheckNames);
                }
                definitions.add("CONSTRAINT " + quote(name) + " CHECK ("
                        + translateExpression(check.expression) + ")");
            }
        }

        if (table.hasEffectiveAutoIncrement() && !isAutoColumnIndexed(table)) {
            Column auto = table.effectiveAutoIncrementColumn();
            definitions.add("KEY " + quote(uniqueName("idx_" + table.name + "_auto", usedIndexNames))
                    + " (" + quote(auto.name) + ")");
            schema.addWarning("Added an index for AUTO_INCREMENT column " + table.name + "." + auto.name);
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
        sb.append(") ").append(tableOptions(table)).append(";").append(nl);
        out.write(sb.toString());
        line("");
    }

    protected List<IndexColumn> primaryKeyParts(Table table) {
        List<IndexColumn> parts = new ArrayList<>();
        for (Column column : table.primaryKeyColumns()) {
            IndexColumn found = null;
            for (IndexColumn part : table.primaryKey) {
                if (part.column().equalsIgnoreCase(column.name)) {
                    found = part;
                    break;
                }
            }
            parts.add(found != null ? found : IndexColumn.of(column.name));
        }
        return parts;
    }

    protected boolean isAutoColumnIndexed(Table table) {
        Column auto = table.effectiveAutoIncrementColumn();
        if (auto == null) {
            return true;
        }
        for (Column pk : table.primaryKeyColumns()) {
            if (pk == auto) {
                return true;
            }
        }
        for (Index index : table.indexes) {
            if (!index.columns.isEmpty() && index.columns.get(0).column().equalsIgnoreCase(auto.name)) {
                return true;
            }
        }
        return false;
    }

    protected String columnDefinition(Table table, Column column) {
        ColumnStats stats = stats(table, column);
        StringBuilder sb = new StringBuilder();
        sb.append(quote(column.name)).append(' ');
        String typeSql = TypeMapper.mysqlType(column, stats, config);
        sb.append(typeSql);

        CharsetChoice charset = charsetFor(table, column, stats);
        if (charset != null) {
            if (!charset.name.equalsIgnoreCase(tableCharset())) {
                sb.append(" CHARACTER SET ").append(charset.name);
            }
            if (charset.collation != null
                    && (!charset.collation.equalsIgnoreCase(tableCollation())
                    || !charset.name.equalsIgnoreCase(tableCharset()))) {
                sb.append(" COLLATE ").append(charset.collation);
            }
        }

        if (column.isPrimaryKey() || column.autoIncrement
                || (column.nullable == false)) {
            sb.append(" NOT NULL");
        }

        if (column.hasDefault && !column.isGenerated()) {
            String def = defaultClause(table, column, stats);
            if (def != null) {
                sb.append(" DEFAULT ").append(def);
            }
        }

        if (column.onUpdate != null && !column.onUpdate.isBlank() && !column.isGenerated()) {
            if (column.type != null && column.type.family.isTemporal()) {
                sb.append(" ON UPDATE ").append(translateExpression(column.onUpdate));
            } else {
                schema.addWarning("Dropped ON UPDATE for non-temporal column " + table.name + "."
                        + column.name);
            }
        }

        if (!column.isGenerated() && (column.autoIncrement || (table.rowidAlias && column == table.singlePrimaryKeyColumn()))) {
            if (column.type != null && column.type.family.isInteger()) {
                sb.append(" AUTO_INCREMENT");
            } else {
                schema.addWarning("Column " + table.name + "." + column.name
                        + " is AUTO_INCREMENT but not an integer type; AUTO_INCREMENT was dropped");
            }
        }

        if (column.generatedExpression != null && !column.generatedExpression.isBlank()) {
            sb.append(" GENERATED ALWAYS AS (").append(translateExpression(column.generatedExpression))
                    .append(") ").append(column.generatedStored ? "STORED" : "VIRTUAL");
        }

        if (column.invisible) {
            boolean supported = (config.isMariaDb() && config.versionAtLeast("10.3.0"))
                    || (config.isMySql() && config.versionAtLeast("8.0.23"));
            if (!supported) {
                schema.addWarning("INVISIBLE column " + table.name + "." + column.name
                        + " is not supported by the target version; emitted as visible");
            } else {
                sb.append(" INVISIBLE");
            }
        }

        if (config.emitColumnComments && column.comment != null && !column.comment.isBlank()) {
            sb.append(" COMMENT ").append(SqlEscaper.mysqlStringLiteral(
                    safeComment(column.comment, 1000)));
        }
        return sb.toString();
    }

    protected String defaultClause(Table table, Column column, ColumnStats stats) {
        Value value = column.defaultValue;
        if (value == null) {
            return null;
        }
        boolean textLike = column.type != null
                && (column.type.family == TypeFamily.TEXT || column.type.family == TypeFamily.BLOB);
        switch (value.kind) {
            case NULL:
                return "NULL";
            case STRING: {
                if (textLike) {
                    if (config.isLegacyMariaDb()) {
                        schema.addWarning("MariaDB 10.1 cannot store a DEFAULT on TEXT/BLOB column "
                                + table.name + "." + column.name + "; default was dropped");
                        return null;
                    }
                    if (config.isMySql()) {
                        if (!config.versionAtLeast("8.0.13")) {
                            schema.addWarning("MySQL before 8.0.13 cannot store a DEFAULT on TEXT/BLOB column "
                                    + table.name + "." + column.name + "; default was dropped");
                            return null;
                        }
                        return "(" + SqlEscaper.mysqlStringLiteral(value.string) + ")";
                    }
                    // MariaDB 10.2+ supports literals on TEXT/BLOB columns.
                    return SqlEscaper.mysqlStringLiteral(value.string);
                }
                return SqlEscaper.mysqlStringLiteral(value.string);
            }
            case BYTES: {
                if (textLike && config.isLegacyMariaDb()) {
                    schema.addWarning("Dropped BLOB default for " + table.name + "." + column.name);
                    return null;
                }
                String literal = SqlEscaper.mysqlBytesLiteral(value.bytes);
                return textLike ? "(" + literal + ")" : literal;
            }
            case NUMBER:
                return plainNumber(value.number);
            case BOOLEAN:
                return "1".equals(value.expression) ? "1" : "0";
            case EXPRESSION: {
                String expr = translateExpression(value.expression);
                if (expr == null || expr.isBlank()) {
                    return null;
                }
                String upper = expr.trim().toUpperCase(Locale.ROOT);
                boolean allowed = upper.startsWith("CURRENT_TIMESTAMP")
                        || upper.equals("CURRENT_DATE") || upper.equals("CURRENT_TIME")
                        || upper.equals("NOW()") || upper.equals("NULL");
                if (!allowed) {
                    if (config.isLegacyMariaDb()) {
                        schema.addWarning("MariaDB 10.1 does not support expression default '"
                                + TextUtils.truncate(expr, 50) + "' on " + table.name + "."
                                + column.name + "; default was dropped");
                        return null;
                    }
                    return "(" + expr + ")";
                }
                return expr;
            }
            case DEFAULT:
            default:
                return null;
        }
    }

    protected CharsetChoice charsetFor(Table table, Column column, ColumnStats stats) {
        if (column.type == null || !column.type.family.isTextual()) {
            return null;
        }
        String cs = tableCharset();
        boolean needsUtf8mb4 = needsUtf8mb4(column, stats);
        if (needsUtf8mb4) {
            if (config.isLegacyMariaDb() && isLegacyIndexUnsafe(table, column) && !stats.hasNonBmp) {
                schema.addWarning("Keeping " + table.name + "." + column.name
                        + " in utf8 (utf8mb3) to fit MariaDB 10.1's 767-byte index limit");
            } else {
                if (config.isLegacyMariaDb() && isLegacyIndexUnsafe(table, column)) {
                    schema.addWarning("Column " + table.name + "." + column.name
                            + " contains 4-byte characters and is indexed; MariaDB 10.1 may reject the index");
                }
                cs = "utf8mb4";
            }
        }
        String collation = collationFor(cs, column);
        return new CharsetChoice(cs, collation);
    }

    protected boolean needsUtf8mb4(Column column, ColumnStats stats) {
        if (stats.hasNonBmp) {
            return true;
        }
        if (column.charset != null && column.charset.toLowerCase(Locale.ROOT).contains("utf8mb4")) {
            return true;
        }
        if (column.hasDefault) {
            Value value = column.defaultValue;
            if (value != null) {
                if (value.kind == Value.Kind.STRING && TextUtils.hasNonBmp(value.string)) {
                    return true;
                }
                if (value.kind == Value.Kind.EXPRESSION && TextUtils.hasNonBmp(value.expression)) {
                    return true;
                }
            }
        }
        if (column.comment != null && TextUtils.hasNonBmp(column.comment)) {
            return true;
        }
        if (column.type != null && column.type.enumValues != null) {
            for (String enumValue : column.type.enumValues) {
                if (TextUtils.hasNonBmp(enumValue)) {
                    return true;
                }
            }
        }
        return false;
    }

    protected boolean isLegacyIndexUnsafe(Table table, Column column) {
        int length = column.type.length == null ? 255 : column.type.length;
        if (length <= config.maxCharsForIndex("utf8mb4")) {
            return false;
        }
        return isIndexed(table, column);
    }

    protected boolean isIndexed(Table table, Column column) {
        for (Column pk : table.primaryKeyColumns()) {
            if (pk == column) {
                return true;
            }
        }
        for (Index index : table.indexes) {
            for (IndexColumn part : index.columns) {
                if (part.column().equalsIgnoreCase(column.name)) {
                    return true;
                }
            }
        }
        return false;
    }

    protected String collationFor(String charset, Column column) {
        boolean binary = column.type != null && column.type.binaryAttribute;
        if (!binary && column.collation != null) {
            binary = column.collation.toLowerCase(Locale.ROOT).endsWith("_bin");
        }
        if (binary) {
            return charset + "_bin";
        }
        if (config.collation != null && !config.collation.isBlank()
                && config.collation.toLowerCase(Locale.ROOT).startsWith(charset.toLowerCase(Locale.ROOT))) {
            return config.collation;
        }
        if (charset.equalsIgnoreCase("utf8mb4")) {
            return "utf8mb4_unicode_ci";
        }
        if (charset.equalsIgnoreCase("utf8") || charset.equalsIgnoreCase("utf8mb3")) {
            return "utf8_general_ci";
        }
        return config.effectiveCollation();
    }

    protected String tableCharset() {
        String cs = config.effectiveCharset();
        return cs == null ? "utf8mb4" : cs;
    }

    protected String tableCollation() {
        String coll = config.effectiveCollation();
        return coll == null ? tableCharset() + "_general_ci" : coll;
    }

    protected String tableOptions(Table table) {
        StringBuilder sb = new StringBuilder();
        String engine = table.engine == null || table.engine.isBlank() ? "InnoDB" : table.engine;
        sb.append("ENGINE=").append(engine);
        sb.append(" DEFAULT CHARSET=").append(tableCharset());
        sb.append(" COLLATE=").append(tableCollation());
        if (!config.isLegacyMariaDb()) {
            sb.append(" ROW_FORMAT=DYNAMIC");
        }
        if (table.hasEffectiveAutoIncrement() && table.autoIncrementValue > 1) {
            sb.append(" AUTO_INCREMENT=").append(table.autoIncrementValue);
        }
        if (table.comment != null && !table.comment.isBlank()) {
            sb.append(" COMMENT=").append(SqlEscaper.mysqlStringLiteral(safeComment(table.comment, 2000)));
        }
        if (table.temporary) {
            schema.addWarning("Table " + table.name + " was temporary in the source; created as a normal table");
        }
        if (table.withoutRowid) {
            schema.addWarning("WITHOUT ROWID on " + table.name + " has no MySQL equivalent");
        }
        if (table.strict) {
            schema.addWarning("STRICT table " + table.name + " has no MySQL equivalent");
        }
        return sb.toString();
    }

    protected String indexDefinition(Table table, Index index, Set<String> usedNames) {
        if (index.columns.isEmpty()) {
            return null;
        }
        if (index.unsupportedReason != null) {
            schema.addWarning("Skipped index on " + table.name + ": " + index.unsupportedReason);
            return null;
        }
        if (index.where != null && !index.where.isBlank()) {
            schema.addWarning("Skipped partial index on " + table.name + " (MySQL has no partial indexes)");
            return null;
        }
        for (IndexColumn part : index.columns) {
            if (table.getColumn(part.column()) == null) {
                schema.addWarning("Skipped index on " + table.name + " with unknown column " + part.column());
                return null;
            }
            Column column = table.getColumn(part.column());
            if (part.prefixLength() == null && column.type != null
                    && (column.type.family == TypeFamily.TEXT || column.type.family == TypeFamily.BLOB
                    || column.type.family == TypeFamily.JSON)) {
                schema.addWarning("Index on TEXT/BLOB column " + table.name + "." + column.name
                        + " may need an explicit prefix in MySQL/MariaDB");
            }
        }
        if (index.unique && sameAsPrimaryKey(table, index)) {
            return null;
        }
        String name = indexName(table, index, usedNames);
        StringBuilder sb = new StringBuilder();
        if (index.isFullText()) {
            sb.append("FULLTEXT KEY ");
        } else if (index.isSpatial()) {
            sb.append("SPATIAL KEY ");
        } else if (index.unique) {
            sb.append("UNIQUE KEY ");
        } else {
            sb.append("KEY ");
        }
        sb.append(quote(name)).append(" (");
        for (int i = 0; i < index.columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            IndexColumn part = index.columns.get(i);
            sb.append(quote(part.column()));
            if (part.prefixLength() != null) {
                sb.append('(').append(part.prefixLength()).append(')');
            }
            if (part.descending()) {
                if (config.isLegacyMariaDb()) {
                    schema.addWarning("DESC index part on " + table.name + " was omitted for MariaDB 10.1");
                } else {
                    sb.append(" DESC");
                }
            }
        }
        sb.append(')');
        if (index.type != null && !index.type.equalsIgnoreCase("BTREE")
                && !index.isFullText() && !index.isSpatial()) {
            sb.append(" USING ").append(index.type);
        }
        if (index.comment != null && !index.comment.isBlank()) {
            sb.append(" COMMENT ").append(SqlEscaper.mysqlStringLiteral(safeComment(index.comment, 1000)));
        }
        warnLegacyIndexLength(table, index, name);
        return sb.toString();
    }

    protected void warnLegacyIndexLength(Table table, Index index, String name) {
        if (!config.isLegacyMariaDb()) {
            return;
        }
        long bytes = 0;
        for (IndexColumn part : index.columns) {
            Column column = table.getColumn(part.column());
            if (column == null || column.type == null || !column.type.family.isTextual()) {
                bytes += 8;
                continue;
            }
            int chars = part.prefixLength() != null ? part.prefixLength()
                    : column.type.lengthOr(255);
            CharsetChoice choice = charsetFor(table, column, stats(table, column));
            String cs = choice == null ? tableCharset() : choice.name;
            bytes += (long) chars * config.bytesPerChar(cs);
        }
        if (bytes > config.maxIndexBytes()) {
            schema.addWarning("Index " + name + " on " + table.name + " is " + bytes
                    + " bytes, over MariaDB 10.1's " + config.maxIndexBytes()
                    + "-byte limit; import may fail");
        }
    }

    protected boolean sameAsPrimaryKey(Table table, Index index) {
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

    protected String indexName(Table table, Index index, Set<String> used) {
        String base = index.name;
        if (base == null || base.isBlank()) {
            StringBuilder sb = new StringBuilder(index.unique ? "uq_" : "idx_");
            sb.append(table.name);
            for (IndexColumn part : index.columns) {
                sb.append('_').append(part.column());
            }
            base = sb.toString();
        }
        return uniqueName(base, used);
    }

    protected String foreignKeyDefinition(Table table, ForeignKey foreignKey, Set<String> usedNames) {
        if (foreignKey.columns.isEmpty()) {
            return null;
        }
        List<String> refColumns = foreignKey.referencedColumns;
        Table refTable = schema.getTable(foreignKey.referencedTable);
        if (refTable == null) {
            schema.addWarning("Skipped foreign key on " + table.name + " referencing unknown table "
                    + foreignKey.referencedTable);
            return null;
        }
        if (refColumns == null || refColumns.isEmpty()) {
            Table ref = refTable;
            if (ref != null) {
                List<Column> pk = ref.primaryKeyColumns();
                List<String> names = new ArrayList<>();
                for (Column c : pk) {
                    names.add(c.name);
                }
                refColumns = names;
            }
        }
        if (refColumns == null || refColumns.isEmpty()) {
            schema.addWarning("Skipped foreign key on " + table.name + " without referenced columns");
            return null;
        }
        if (foreignKey.columns.size() != refColumns.size()) {
            schema.addWarning("Skipped foreign key on " + table.name + " with mismatched column counts");
            return null;
        }
        String name = foreignKey.name;
        if (name == null || name.isBlank()) {
            name = "fk_" + table.name + "_" + String.join("_", foreignKey.columns);
        }
        name = uniqueName(name, usedNames);
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
            sb.append(" ON DELETE ").append(normalizeAction(foreignKey.onDelete));
        }
        if (foreignKey.onUpdate != null) {
            sb.append(" ON UPDATE ").append(normalizeAction(foreignKey.onUpdate));
        }
        return sb.toString();
    }

    protected String normalizeAction(String action) {
        String a = action.trim().toUpperCase(Locale.ROOT);
        if (a.equals("SET DEFAULT")) {
            schema.addWarning("SET DEFAULT foreign-key action is not supported by InnoDB; using RESTRICT");
            return "RESTRICT";
        }
        return a;
    }

    protected String translateExpression(String raw) {
        if (raw == null) {
            return "";
        }
        String expression = raw.trim();
        expression = expression.replaceAll("(?i)_(utf8mb4|utf8mb3|utf8|latin1|binary)\\s*'", "'");
        if (expression.contains("\"")) {
            expression = convertDoubleQuotedToBackticks(expression);
        }
        String ciCollation = tableCharset().equalsIgnoreCase("utf8mb4")
                ? "utf8mb4_general_ci" : "utf8_general_ci";
        expression = expression.replaceAll("(?i)\\bNOCASE\\b", ciCollation);
        expression = expression.replaceAll("(?i)\\bCOLLATE\\s+BINARY\\b",
                "COLLATE " + (tableCharset().equalsIgnoreCase("utf8mb4") ? "utf8mb4_bin" : "utf8_bin"));
        return expression;
    }

    protected static String convertDoubleQuotedToBackticks(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\'') {
                int end = findStringEnd(input, i, '\'');
                sb.append(input, i, end);
                i = end - 1;
            } else if (c == '"') {
                int end = findStringEnd(input, i, '"');
                String content = input.substring(i + 1, end - 1);
                sb.append('`').append(content.replace("`", "``")).append('`');
                i = end - 1;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    protected static int findStringEnd(String input, int start, char quote) {
        int i = start + 1;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c == '\\' && i + 1 < input.length()) {
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

    protected String uniqueName(String base, Set<String> used) {
        String name = TextUtils.sanitizeIdentifier(base, 60);
        String candidate = name;
        int suffix = 2;
        while (used.contains(candidate.toLowerCase(Locale.ROOT))) {
            String s = "_" + suffix;
            candidate = (name.length() + s.length() > 64 ? name.substring(0, 64 - s.length()) : name) + s;
            suffix++;
        }
        used.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }

    protected ColumnStats stats(Table table, Column column) {
        String key = table.name + "\0" + column.name;
        return statsCache.computeIfAbsent(key, k -> ColumnStats.of(table, column));
    }

    @Override
    protected String quote(String identifier) {
        return SqlEscaper.mysqlIdentifier(identifier);
    }

    @Override
    protected String valueLiteral(Value value, Column column, Table table) {
        if (value == null || value.kind == Value.Kind.NULL) {
            return "NULL";
        }
        return switch (value.kind) {
            case STRING -> SqlEscaper.mysqlStringLiteral(value.string);
            case BYTES -> SqlEscaper.mysqlBytesLiteral(value.bytes);
            case NUMBER -> plainNumber(value.number);
            case BOOLEAN -> "1".equals(value.expression) ? "1" : "0";
            case DEFAULT -> "DEFAULT";
            case EXPRESSION -> translateExpression(value.expression);
            case NULL -> "NULL";
        };
    }

    @Override
    protected String dialectName() {
        return config == null || config.isMariaDb() ? "MariaDB" : "MySQL";
    }

    @Override
    protected String emptyRowInsert(Table table) {
        return "INSERT INTO " + quote(table.name) + " () VALUES ();";
    }

    protected record CharsetChoice(String name, String collation) {
    }
}
