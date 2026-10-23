package cc.thonly.sqlswitch.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The in-memory result of parsing a dump or reading a SQLite database.
 */
public final class Schema {
    public String name;
    public final List<Table> tables = new ArrayList<>();
    public final Map<String, Long> sequences = new LinkedHashMap<>();
    public final List<String> warnings = new ArrayList<>();
    public final java.util.Set<String> skippedTables = new java.util.LinkedHashSet<>();
    public SourceInfo source = new SourceInfo();

    private final Map<String, Table> tableLookup = new LinkedHashMap<>();

    public void addTable(Table table) {
        tables.add(table);
        tableLookup.put(table.name.toLowerCase(Locale.ROOT), table);
    }

    public void replaceTable(Table table) {
        Table old = tableLookup.get(table.name.toLowerCase(Locale.ROOT));
        if (old != null) {
            int idx = tables.indexOf(old);
            if (idx >= 0) {
                tables.set(idx, table);
            }
            tableLookup.put(table.name.toLowerCase(Locale.ROOT), table);
        } else {
            addTable(table);
        }
    }

    public Table getTable(String tableName) {
        if (tableName == null) {
            return null;
        }
        return tableLookup.get(unquote(tableName).toLowerCase(Locale.ROOT));
    }

    public boolean hasTable(String tableName) {
        return getTable(tableName) != null;
    }

    private static String unquote(String value) {
        String v = value.trim();
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`')
                    || (first == '[' && last == ']')) {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    public void addWarning(String warning) {
        if (warning != null && !warning.isBlank()) {
            warnings.add(warning);
        }
    }

    public void addSkippedTable(String tableName) {
        if (tableName != null && !tableName.isBlank()) {
            skippedTables.add(tableName.toLowerCase(Locale.ROOT));
        }
    }

    public boolean isSkippedTable(String tableName) {
        return tableName != null && skippedTables.contains(tableName.toLowerCase(Locale.ROOT));
    }

    public long totalRows() {
        long total = 0;
        for (Table table : tables) {
            total += table.rows.size();
        }
        return total;
    }

    public long totalColumns() {
        long total = 0;
        for (Table table : tables) {
            total += table.columns.size();
        }
        return total;
    }

    public Table requireTable(String tableName) {
        Table table = getTable(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Table not found: " + tableName);
        }
        return table;
    }

    @Override
    public String toString() {
        return "Schema{" + (name == null ? "" : name + ", ") + tables.size() + " tables, "
                + totalRows() + " rows}";
    }
}
