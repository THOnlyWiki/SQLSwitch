package cc.thonly.sqlswitch.write;

import cc.thonly.sqlswitch.model.Column;
import cc.thonly.sqlswitch.model.Row;
import cc.thonly.sqlswitch.model.Schema;
import cc.thonly.sqlswitch.model.Table;
import cc.thonly.sqlswitch.model.TargetConfig;
import cc.thonly.sqlswitch.model.Value;
import cc.thonly.sqlswitch.util.Log;
import cc.thonly.sqlswitch.util.TextUtils;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared output helpers, most importantly byte-bounded multi-row INSERT
 * generation.  MySQL/MariaDB have a relatively small default
 * max_allowed_packet on older installations, so statements must not grow
 * without bound.
 */
public abstract class AbstractSqlWriter implements SqlWriter {
    protected Schema schema;
    protected TargetConfig config;
    protected Writer out;
    protected final String nl = "\n";

    protected abstract String quote(String identifier);

    protected abstract String valueLiteral(Value value, Column column, Table table);

    protected abstract String dialectName();

    protected abstract String emptyRowInsert(Table table);

    protected void setup(Schema schema, TargetConfig config, Writer out) {
        this.schema = schema;
        this.config = config;
        this.out = out;
    }

    protected void line(String text) throws IOException {
        out.write(text);
        out.write(nl);
    }

    protected void comment(String text) throws IOException {
        String clean = text == null ? "" : text.replace('\r', ' ').replace('\n', ' ');
        line("-- " + clean);
    }

    protected void writeHeader() throws IOException {
        if (!config.header) {
            return;
        }
        comment("SQLSwitch " + version() + " - SQL dump converter");
        comment("Source: " + schema.source.describe());
        comment("Target: " + dialectName()
                + (config.serverVersion == null ? "" : " " + config.serverVersion));
        comment("Tables: " + schema.tables.size() + ", rows: " + schema.totalRows()
                + ", columns: " + schema.totalColumns());
        if (!config.noTimestamp) {
            comment("Generated: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
        if (!schema.warnings.isEmpty()) {
            comment("Warnings: " + schema.warnings.size());
            int limit = Math.min(40, schema.warnings.size());
            for (int i = 0; i < limit; i++) {
                comment("WARNING: " + schema.warnings.get(i));
            }
            if (schema.warnings.size() > limit) {
                comment("WARNING: ... " + (schema.warnings.size() - limit) + " more warnings");
            }
        }
        line("");
    }

    protected static String version() {
        String v = AbstractSqlWriter.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "1.0.0" : v;
    }

    protected String columnList(List<Integer> indexes, Table table) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indexes.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(quote(table.columns.get(indexes.get(i)).name));
        }
        return sb.toString();
    }

    protected void writeInserts(Table table) throws IOException {
        if (!config.includeData || table.rows.isEmpty()) {
            return;
        }
        List<Row> rows = rowsFor(table);
        List<Integer> writable = new ArrayList<>();
        for (int i = 0; i < table.columns.size(); i++) {
            if (!table.columns.get(i).isGenerated()) {
                writable.add(i);
            }
        }
        if (writable.isEmpty()) {
            return;
        }
        int rowIndex = 0;
        while (rowIndex < rows.size()) {
            Row first = rows.get(rowIndex);
            List<Integer> columns = presentColumns(first, writable);
            if (columns.isEmpty()) {
                // A row that relies entirely on column defaults.
                while (rowIndex < rows.size()
                        && presentColumns(rows.get(rowIndex), writable).isEmpty()) {
                    out.write(emptyRowInsert(table));
                    out.write(nl);
                    rowIndex++;
                }
                continue;
            }
            int end = rowIndex;
            StringBuilder statement = new StringBuilder();
            statement.append("INSERT INTO ").append(quote(table.name));
            statement.append(" (").append(columnList(columns, table)).append(")");
            statement.append(" VALUES").append(nl);
            int inBatch = 0;
            int statementBytes = statement.toString().getBytes(StandardCharsets.UTF_8).length;
            while (end < rows.size()) {
                Row row = rows.get(end);
                if (!sameColumns(row, columns, writable)) {
                    break;
                }
                String literal = rowLiteral(row, columns, table);
                int literalBytes = literal.getBytes(StandardCharsets.UTF_8).length;
                if (inBatch > 0 && config.maxRowsPerInsert > 0 && inBatch >= config.maxRowsPerInsert) {
                    break;
                }
                if (inBatch > 0 && config.maxStatementBytes > 0
                        && statementBytes + literalBytes + 2 > config.maxStatementBytes) {
                    break;
                }
                if (inBatch > 0) {
                    statement.append(',').append(nl);
                }
                statement.append(literal);
                statementBytes += literalBytes + 2;
                inBatch++;
                end++;
            }
            statement.append(';').append(nl);
            if (config.maxStatementBytes > 0 && statementBytes > config.maxStatementBytes) {
                schema.addWarning("One INSERT statement for " + table.name + " is larger than "
                        + config.maxStatementBytes + " bytes; the server may reject it");
            }
            out.write(statement.toString());
            rowIndex = end;
        }
    }

    protected List<Row> rowsFor(Table table) {
        if (!config.sortRows || table.primaryKeyColumns().isEmpty() || table.rows.size() < 2) {
            return table.rows;
        }
        List<Row> rows = new ArrayList<>(table.rows);
        rows.sort((a, b) -> compareByPrimaryKey(table, a, b));
        return rows;
    }

    private int compareByPrimaryKey(Table table, Row a, Row b) {
        for (Column column : table.primaryKeyColumns()) {
            int idx = table.columns.indexOf(column);
            int cmp = compareValues(a.values[idx], b.values[idx]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private int compareValues(Value left, Value right) {
        if (left == null || left.isNull()) {
            return right == null || right.isNull() ? 0 : -1;
        }
        if (right == null || right.isNull()) {
            return 1;
        }
        if (left.kind == Value.Kind.NUMBER && right.kind == Value.Kind.NUMBER) {
            return left.number.compareTo(right.number);
        }
        if (left.kind == Value.Kind.BOOLEAN && right.kind == Value.Kind.BOOLEAN) {
            return left.expression.compareTo(right.expression);
        }
        if (left.kind == Value.Kind.STRING && right.kind == Value.Kind.STRING) {
            return left.string.compareTo(right.string);
        }
        if (left.kind == Value.Kind.BYTES && right.kind == Value.Kind.BYTES) {
            return java.util.Arrays.compareUnsigned(left.bytes, right.bytes);
        }
        return left.canonical().compareTo(right.canonical());
    }

    private List<Integer> presentColumns(Row row, List<Integer> writable) {
        List<Integer> result = new ArrayList<>();
        for (int index : writable) {
            if (row.isPresent(index)) {
                result.add(index);
            }
        }
        return result;
    }

    private boolean sameColumns(Row row, List<Integer> columns, List<Integer> writable) {
        int p = 0;
        for (int index : writable) {
            if (row.isPresent(index)) {
                if (p >= columns.size() || columns.get(p) != index) {
                    return false;
                }
                p++;
            }
        }
        return p == columns.size();
    }

    protected String rowLiteral(Row row, List<Integer> columns, Table table) {
        if (columns.isEmpty()) {
            return "()";
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            int index = columns.get(i);
            sb.append(valueLiteral(row.values[index], table.columns.get(index), table));
        }
        sb.append(')');
        return sb.toString();
    }

    protected void writeTableLabel(String tableName) throws IOException {
        if (!config.comments) {
            return;
        }
        comment("----------------------------");
        comment("Table structure for " + tableName);
        comment("----------------------------");
    }

    protected void writeDataLabel(String tableName, int rows) throws IOException {
        if (!config.comments) {
            return;
        }
        comment("----------------------------");
        comment("Records of " + tableName + " (" + rows + " rows)");
        comment("----------------------------");
    }

    protected String plainNumber(java.math.BigDecimal number) {
        if (number == null) {
            return "0";
        }
        String s = number.stripTrailingZeros().toPlainString();
        if (s.isEmpty() || s.equals("-0")) {
            return "0";
        }
        return s;
    }

    protected String safeComment(String text, int max) {
        if (text == null) {
            return "";
        }
        String s = text.replace("\r", " ").replace("\n", " ");
        return s.length() <= max ? s : s.substring(0, max);
    }

    protected void detail(String message) {
        Log.detail(message);
    }

    protected String utf8(String text) {
        return text == null ? "" : text;
    }

    protected int utf8Length(String text) {
        return TextUtils.utf8Length(text);
    }
}
