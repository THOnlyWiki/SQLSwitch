package cc.thonly.sqlswitch.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A table with its schema, keys, constraints and data.
 */
public final class Table {
    public String name;
    public final List<Column> columns = new ArrayList<>();
    public final List<IndexColumn> primaryKey = new ArrayList<>();
    public final List<Index> indexes = new ArrayList<>();
    public final List<ForeignKey> foreignKeys = new ArrayList<>();
    public final List<CheckConstraint> checks = new ArrayList<>();
    public final List<Row> rows = new ArrayList<>();

    public String engine;
    public String charset;
    public String collation;
    public String rowFormat;
    public String comment;
    public long autoIncrementValue;
    public boolean withoutRowid;
    public boolean strict;
    public boolean temporary;
    public String rawCreateSql;
    public int sourceOrder;
    /**
     * SQLite treats a single-column INTEGER PRIMARY KEY as an alias for the
     * hidden rowid, which auto-generates values even without AUTOINCREMENT.
     * MySQL/MariaDB should get AUTO_INCREMENT for the same behaviour.
     */
    public boolean rowidAlias;

    private final Map<String, Column> columnLookup = new LinkedHashMap<>();

    public Table() {
    }

    public Table(String name) {
        this.name = name;
    }

    public void addColumn(Column column) {
        columns.add(column);
        columnLookup.put(column.name.toLowerCase(Locale.ROOT), column);
    }

    public Column getColumn(String columnName) {
        if (columnName == null) {
            return null;
        }
        return columnLookup.get(unquote(columnName).toLowerCase(Locale.ROOT));
    }

    public int indexOfColumn(String columnName) {
        Column c = getColumn(columnName);
        return c == null ? -1 : columns.indexOf(c);
    }

    private static String unquote(String value) {
        String v = value.trim();
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`')
                    || (first == '[' && last == ']') || (first == '\'' && last == '\'')) {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    public List<Column> nonGeneratedColumns() {
        List<Column> result = new ArrayList<>();
        for (Column c : columns) {
            if (!c.isGenerated()) {
                result.add(c);
            }
        }
        return result;
    }

    public List<Column> primaryKeyColumns() {
        List<Column> result = new ArrayList<>();
        for (Column c : columns) {
            if (c.primaryKeyOrder > 0) {
                result.add(c);
            }
        }
        result.sort(Comparator.comparingInt(c -> c.primaryKeyOrder));
        if (result.isEmpty()) {
            for (IndexColumn ic : primaryKey) {
                Column c = getColumn(ic.column());
                if (c != null) {
                    result.add(c);
                }
            }
        }
        return result;
    }

    public Column singlePrimaryKeyColumn() {
        List<Column> pk = primaryKeyColumns();
        return pk.size() == 1 ? pk.get(0) : null;
    }

    public Column autoIncrementColumn() {
        for (Column c : columns) {
            if (c.autoIncrement) {
                return c;
            }
        }
        return null;
    }

    public boolean hasAutoIncrement() {
        return autoIncrementColumn() != null;
    }

    /**
     * The column that behaves as an auto-generated key in the target family.
     * For SQLite, an INTEGER PRIMARY KEY is an auto-generating rowid alias even
     * without the AUTOINCREMENT keyword.
     */
    public Column effectiveAutoIncrementColumn() {
        Column explicit = autoIncrementColumn();
        if (explicit != null) {
            return explicit;
        }
        if (rowidAlias) {
            Column pk = singlePrimaryKeyColumn();
            if (pk != null && pk.type != null && pk.type.family.isInteger()) {
                return pk;
            }
        }
        return null;
    }

    public boolean hasEffectiveAutoIncrement() {
        return effectiveAutoIncrementColumn() != null;
    }

    public List<Index> uniqueIndexes() {
        List<Index> result = new ArrayList<>();
        for (Index index : indexes) {
            if (index.unique) {
                result.add(index);
            }
        }
        return result;
    }

    public Index findIndexByColumns(boolean unique, List<String> columnNames) {
        String wanted = String.join(",", columnNames.stream()
                .map(s -> s.toLowerCase(Locale.ROOT)).toList());
        for (Index index : indexes) {
            if (index.unique == unique && index.columnSignature().equals(wanted)) {
                return index;
            }
        }
        return null;
    }

    public int rowCount() {
        return rows.size();
    }

    @Override
    public String toString() {
        return name + " (" + columns.size() + " columns, " + rows.size() + " rows)";
    }
}
