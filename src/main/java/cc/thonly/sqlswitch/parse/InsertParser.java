package cc.thonly.sqlswitch.parse;

import cc.thonly.sqlswitch.model.Column;
import cc.thonly.sqlswitch.model.Dialect;
import cc.thonly.sqlswitch.model.Row;
import cc.thonly.sqlswitch.model.Schema;
import cc.thonly.sqlswitch.model.Table;
import cc.thonly.sqlswitch.model.Value;
import cc.thonly.sqlswitch.util.TextUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses INSERT/REPLACE statements.  Consecutive extended-value tuples are
 * supported, as are column lists, DEFAULT values and sqlite_sequence rows.
 */
public final class InsertParser {
    private final Schema schema;
    private final ValueParser valueParser;
    private final CreateTableParser createTableParser;
    private final Dialect dialect;

    public InsertParser(Schema schema, ValueParser valueParser, CreateTableParser createTableParser,
                        Dialect dialect) {
        this.schema = schema;
        this.valueParser = valueParser;
        this.createTableParser = createTableParser;
        this.dialect = dialect;
    }

    public void parse(TokenStream ts, int line, boolean replace) {
        while (ts.matchKeyword("LOW_PRIORITY") || ts.matchKeyword("DELAYED")
                || ts.matchKeyword("HIGH_PRIORITY")) {
            // modifiers
        }
        boolean ignore = ts.matchKeyword("IGNORE");
        ts.matchKeyword("INTO");
        String qualified = ts.readQualifiedIdentifier();
        String tableName = TextUtils.stripSchema(qualified);

        if (tableName.equalsIgnoreCase("sqlite_sequence")) {
            parseSqliteSequence(ts);
            return;
        }

        Table table = schema.getTable(tableName);
        if (table == null) {
            if (schema.isSkippedTable(tableName)) {
                schema.addWarning("INSERT into skipped table " + tableName + " was skipped (line " + line + ")");
                ts.skipStatementTail();
                return;
            }
            throw new ParseException("INSERT references unknown table " + tableName, ts.peek().line, ts.peek().column);
        }
        if (replace) {
            schema.addWarning("REPLACE INTO " + tableName + " was treated as INSERT (line " + line + ")");
        }
        if (ignore) {
            schema.addWarning("INSERT IGNORE semantics are not reproduced for " + tableName + " (line " + line + ")");
        }

        List<String> columnNames = null;
        if (ts.atSymbol("(")) {
            columnNames = createTableParser.parseColumnNameList(ts);
        }

        if (ts.matchKeyword("DEFAULT") && ts.matchKeyword("VALUES")) {
            addRow(table, columnNames, List.of());
            return;
        }

        if (ts.matchKeyword("SET")) {
            parseSetSyntax(table, ts, line);
            return;
        }

        if (ts.peek().isKeyword("SELECT") || ts.peek().isKeyword("WITH")) {
            schema.addWarning("INSERT ... SELECT is not supported and was skipped for " + tableName
                    + " (line " + line + ")");
            return;
        }

        if (!ts.matchKeyword("VALUES") && !ts.matchKeyword("VALUE")) {
            Token t = ts.peek();
            throw new ParseException("Expected VALUES for INSERT into " + tableName
                    + " but found '" + t.text + "'", t.line, t.column);
        }

        while (!ts.atEnd() && !ts.atSymbol(";")) {
            if (ts.matchSymbol(",")) {
                continue;
            }
            if (!ts.atSymbol("(")) {
                break;
            }
            ts.next(); // (
            List<Value> values = new ArrayList<>();
            if (!ts.atSymbol(")")) {
                while (!ts.atEnd()) {
                    Value value = valueParser.parseValue(ts);
                    values.add(value);
                    if (ts.matchSymbol(",")) {
                        continue;
                    }
                    break;
                }
            }
            ts.expectSymbol(")");
            addRow(table, columnNames, values);
            if (!ts.matchSymbol(",")) {
                break;
            }
        }

        if (ts.peek().isKeyword("ON")) {
            schema.addWarning("ON DUPLICATE KEY UPDATE clause was ignored for " + tableName
                    + " (line " + line + ")");
            ts.skipStatementTail();
        }
    }

    private void parseSetSyntax(Table table, TokenStream ts, int line) {
        Map<String, Value> assignments = new LinkedHashMap<>();
        while (!ts.atEnd() && !ts.atSymbol(";")) {
            String name = ts.expectIdentifier();
            ts.expectSymbol("=");
            Value value = valueParser.parseValue(ts);
            assignments.put(name, value);
            if (!ts.matchSymbol(",")) {
                break;
            }
        }
        List<String> columns = new ArrayList<>(assignments.keySet());
        List<Value> values = new ArrayList<>(assignments.values());
        addRow(table, columns, values);
        if (ts.peek().isKeyword("ON")) {
            schema.addWarning("ON DUPLICATE KEY UPDATE clause was ignored for " + table.name
                    + " (line " + line + ")");
            ts.skipStatementTail();
        }
    }

    private void addRow(Table table, List<String> columnNames, List<Value> values) {
        Value[] rowValues = new Value[table.columns.size()];
        boolean[] present = new boolean[table.columns.size()];
        for (int i = 0; i < rowValues.length; i++) {
            rowValues[i] = Value.NULL;
        }

        if (columnNames != null && columnNames.isEmpty()) {
            if (!values.isEmpty()) {
                throw new ParseException("INSERT into " + table.name
                        + " has an empty column list but supplies values", -1, -1);
            }
            table.rows.add(new Row(rowValues, present));
            return;
        }

        if (columnNames == null) {
            List<Column> columns = table.nonGeneratedColumns();
            if (values.size() == table.columns.size()) {
                // The source may include generated columns in a positional INSERT.
                for (int i = 0; i < values.size(); i++) {
                    Column column = table.columns.get(i);
                    if (column.isGenerated()) {
                        schema.addWarning("Ignoring value for generated column " + table.name + "."
                                + column.name);
                        continue;
                    }
                    setValue(table, column, values.get(i), rowValues, present);
                }
            } else if (values.size() == columns.size()) {
                for (int i = 0; i < values.size(); i++) {
                    setValue(table, columns.get(i), values.get(i), rowValues, present);
                }
            } else {
                throw new ParseException("INSERT into " + table.name + " has " + values.size()
                        + " values but the table has " + columns.size() + " writable columns",
                        -1, -1);
            }
        } else {
            if (values.size() != columnNames.size()) {
                throw new ParseException("INSERT into " + table.name + " lists " + columnNames.size()
                        + " columns but supplies " + values.size() + " values", -1, -1);
            }
            for (int i = 0; i < columnNames.size(); i++) {
                String columnName = columnNames.get(i);
                Column column = table.getColumn(columnName);
                if (column == null) {
                    throw new ParseException("INSERT into " + table.name + " references unknown column "
                            + columnName, -1, -1);
                }
                if (column.isGenerated()) {
                    schema.addWarning("Ignoring value for generated column " + table.name + "." + column.name);
                    continue;
                }
                setValue(table, column, values.get(i), rowValues, present);
            }
        }

        table.rows.add(new Row(rowValues, present));
    }

    private void setValue(Table table, Column column, Value value, Value[] rowValues, boolean[] present) {
        int index = table.columns.indexOf(column);
        if (value != null && value.isDefault()) {
            rowValues[index] = Value.NULL;
            present[index] = false;
            return;
        }
        if (dialect == Dialect.SQLITE) {
            value = applySqliteAffinity(value, column);
        }
        rowValues[index] = value == null ? Value.NULL : value;
        present[index] = true;
    }

    /**
     * SQLite applies column affinity when a value is inserted.  Dumps often
     * quote numeric values (for example Navicat writes '1' for an INTEGER
     * column), so reproducing this step keeps the in-memory model identical to
     * the actual database contents.
     */
    private Value applySqliteAffinity(Value value, Column column) {
        if (value == null || value.kind == Value.Kind.NULL
                || value.kind == Value.Kind.DEFAULT || value.kind == Value.Kind.EXPRESSION) {
            return value;
        }
        Affinity affinity = affinity(column);
        if (affinity == Affinity.BLOB) {
            return value;
        }
        if (value.kind == Value.Kind.BYTES) {
            return value;
        }
        if (affinity == Affinity.TEXT) {
            if (value.kind == Value.Kind.STRING) {
                return value;
            }
            if (value.kind == Value.Kind.NUMBER) {
                return Value.string(plainNumber(value.number));
            }
            if (value.kind == Value.Kind.BOOLEAN) {
                return Value.string("1".equals(value.expression) ? "1" : "0");
            }
            return value;
        }
        if (value.kind == Value.Kind.BOOLEAN) {
            return Value.number("1".equals(value.expression) ? 1 : 0);
        }
        if (value.kind == Value.Kind.STRING) {
            String text = value.string.trim();
            if (affinity == Affinity.INTEGER || affinity == Affinity.NUMERIC || affinity == Affinity.REAL) {
                BigDecimal number = parseSqliteNumber(text);
                if (number != null) {
                    if (affinity != Affinity.REAL && number.stripTrailingZeros().scale() <= 0) {
                        return Value.number(number.setScale(0));
                    }
                    return Value.number(number);
                }
            }
            return value;
        }
        if (value.kind == Value.Kind.NUMBER && affinity != Affinity.REAL) {
            BigDecimal number = value.number;
            if (number != null && number.stripTrailingZeros().scale() <= 0) {
                return Value.number(number.setScale(0));
            }
        }
        return value;
    }

    private static Affinity affinity(Column column) {
        if (column.type == null) {
            return Affinity.BLOB;
        }
        return switch (column.type.family) {
            case INTEGER, BOOLEAN, BIT, YEAR -> Affinity.INTEGER;
            case CHAR, VARCHAR, TEXT, JSON, ENUM, SET, UUID, INET -> Affinity.TEXT;
            case FLOAT, DOUBLE -> Affinity.REAL;
            case DECIMAL -> Affinity.NUMERIC;
            case BINARY, VARBINARY, BLOB, GEOMETRY -> Affinity.BLOB;
            case UNKNOWN -> column.type.isPresent() ? Affinity.NUMERIC : Affinity.BLOB;
            default -> Affinity.NUMERIC;
        };
    }

    private static BigDecimal parseSqliteNumber(String text) {
        if (text.isEmpty()) {
            return null;
        }
        if (text.matches("[+-]?\\d+")) {
            try {
                return new BigDecimal(new java.math.BigInteger(text));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (text.matches("[+-]?(\\d+\\.\\d*|\\.\\d+|\\d+)([eE][+-]?\\d+)?")) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String plainNumber(BigDecimal number) {
        if (number == null) {
            return "0";
        }
        String s = number.stripTrailingZeros().toPlainString();
        return s.isEmpty() || s.equals("-0") ? "0" : s;
    }

    private enum Affinity {
        INTEGER,
        TEXT,
        REAL,
        NUMERIC,
        BLOB
    }

    private void parseSqliteSequence(TokenStream ts) {
        List<String> columns = null;
        if (ts.atSymbol("(")) {
            columns = createTableParser.parseColumnNameList(ts);
        }
        if (!ts.matchKeyword("VALUES")) {
            return;
        }
        while (!ts.atEnd() && !ts.atSymbol(";")) {
            if (ts.matchSymbol(",")) {
                continue;
            }
            if (!ts.atSymbol("(")) {
                break;
            }
            ts.next();
            List<Value> values = new ArrayList<>();
            while (!ts.atEnd() && !ts.atSymbol(")")) {
                values.add(valueParser.parseValue(ts));
                if (!ts.matchSymbol(",")) {
                    break;
                }
            }
            ts.expectSymbol(")");
            String name = null;
            long seq = 0;
            if (columns != null) {
                for (int i = 0; i < Math.min(columns.size(), values.size()); i++) {
                    String col = columns.get(i).toLowerCase(Locale.ROOT);
                    if (col.equals("name") && values.get(i).kind == Value.Kind.STRING) {
                        name = values.get(i).string;
                    } else if (col.equals("seq") && values.get(i).kind == Value.Kind.NUMBER) {
                        seq = values.get(i).number.longValue();
                    }
                }
            } else {
                if (!values.isEmpty() && values.get(0).kind == Value.Kind.STRING) {
                    name = values.get(0).string;
                }
                if (values.size() > 1 && values.get(1).kind == Value.Kind.NUMBER) {
                    seq = values.get(1).number.longValue();
                }
            }
            if (name != null) {
                schema.sequences.put(name, seq);
            }
            if (!ts.matchSymbol(",")) {
                break;
            }
        }
    }

    public static long longValue(Value value) {
        if (value == null) {
            return 0;
        }
        if (value.kind == Value.Kind.NUMBER) {
            BigDecimal number = value.number;
            return number == null ? 0 : number.longValue();
        }
        return 0;
    }
}
