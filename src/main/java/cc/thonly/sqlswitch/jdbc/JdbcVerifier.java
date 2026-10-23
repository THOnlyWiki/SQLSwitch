package cc.thonly.sqlswitch.jdbc;

import cc.thonly.sqlswitch.model.Column;
import cc.thonly.sqlswitch.model.Dialect;
import cc.thonly.sqlswitch.model.Row;
import cc.thonly.sqlswitch.model.Schema;
import cc.thonly.sqlswitch.model.Table;
import cc.thonly.sqlswitch.model.Value;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compares the in-memory source model with the actual rows in an imported
 * MySQL/MariaDB database.  Rows are compared as multisets, so physical row
 * order does not matter.
 */
public final class JdbcVerifier {
    public record Result(
            int tablesChecked,
            long rowsChecked,
            long mismatchedRows,
            List<String> problems,
            List<String> notes
    ) {
        public boolean ok() {
            return problems.isEmpty();
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(ok() ? "VERIFIED" : "FAILED");
            sb.append(": ").append(tablesChecked).append(" tables, ").append(rowsChecked)
                    .append(" rows");
            if (mismatchedRows > 0) {
                sb.append(", ").append(mismatchedRows).append(" mismatched rows");
            }
            if (!problems.isEmpty()) {
                sb.append(", ").append(problems.size()).append(" problems");
            }
            return sb.toString();
        }
    }

    public Result verify(Schema source, JdbcApplier.Target target) throws SQLException {
        if (target.database == null || target.database.isBlank()) {
            throw new IllegalArgumentException("--database is required");
        }
        if (target.dialect == Dialect.MYSQL) {
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        } else {
            try {
                Class.forName("org.mariadb.jdbc.Driver");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        int port = target.port <= 0 ? 3306 : target.port;
        String prefix = target.dialect == Dialect.MYSQL ? "jdbc:mysql://" : "jdbc:mariadb://";
        String url = prefix + target.host + ":" + port + "/" + target.database
                + "?useUnicode=true&characterEncoding=UTF-8";
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        long rowsChecked = 0;
        long mismatched = 0;
        int tablesChecked = 0;
        try (Connection connection = DriverManager.getConnection(url, target.user, target.password)) {
            Map<String, String> actualTables = new LinkedHashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SHOW TABLES")) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    actualTables.put(name.toLowerCase(Locale.ROOT), name);
                }
            }
            for (Table table : source.tables) {
                String actualName = actualTables.get(table.name.toLowerCase(Locale.ROOT));
                if (actualName == null) {
                    problems.add("Missing table: " + table.name);
                    continue;
                }
                tablesChecked++;
                TableDiff diff = verifyTable(connection, table, actualName);
                rowsChecked += table.rows.size();
                mismatched += diff.mismatchedRows;
                problems.addAll(diff.problems);
            }
            for (Map.Entry<String, String> entry : actualTables.entrySet()) {
                if (source.getTable(entry.getValue()) == null) {
                    notes.add("Extra table in target: " + entry.getValue());
                }
            }
        }
        return new Result(tablesChecked, rowsChecked, mismatched, problems, notes);
    }

    private TableDiff verifyTable(Connection connection, Table table, String actualName) throws SQLException {
        List<String> problems = new ArrayList<>();
        long mismatched = 0;
        String sql = "SELECT * FROM `" + actualName.replace("`", "``") + "`";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            Map<String, Integer> targetIndexes = new HashMap<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                targetIndexes.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), i);
            }
            List<Column> compareColumns = new ArrayList<>();
            List<Integer> sourceIndexes = new ArrayList<>();
            List<Integer> targetIndexList = new ArrayList<>();
            for (int i = 0; i < table.columns.size(); i++) {
                Column column = table.columns.get(i);
                if (column.isGenerated()) {
                    continue;
                }
                Integer targetIndex = targetIndexes.get(column.name.toLowerCase(Locale.ROOT));
                if (targetIndex == null) {
                    problems.add("Table " + table.name + " is missing column " + column.name);
                    continue;
                }
                compareColumns.add(column);
                sourceIndexes.add(i);
                targetIndexList.add(targetIndex);
            }

            Map<String, Integer> expected = new HashMap<>();
            for (Row row : table.rows) {
                StringBuilder key = new StringBuilder();
                for (int idx : sourceIndexes) {
                    if (!row.present[idx]) {
                        continue;
                    }
                    key.append(row.values[idx].canonical()).append('\u0001');
                }
                expected.merge(key.toString(), 1, Integer::sum);
            }

            Map<String, Integer> actual = new HashMap<>();
            int rowNumber = 0;
            while (rs.next()) {
                rowNumber++;
                StringBuilder key = new StringBuilder();
                for (int targetIndex : targetIndexList) {
                    Object object = rs.getObject(targetIndex);
                    key.append(Value.fromJava(normalizeJdbcValue(object)).canonical()).append('\u0001');
                }
                actual.merge(key.toString(), 1, Integer::sum);
            }

            int expectedSize = table.rows.size();
            int actualSize = rowNumber;
            if (expectedSize != actualSize) {
                problems.add("Table " + table.name + ": expected " + expectedSize
                        + " rows, found " + actualSize);
            }
            int rowIndex = 0;
            for (Map.Entry<String, Integer> entry : expected.entrySet()) {
                Integer actualCount = actual.get(entry.getKey());
                int ac = actualCount == null ? 0 : actualCount;
                if (ac != entry.getValue()) {
                    mismatched += Math.abs(ac - entry.getValue());
                    if (mismatched <= 10) {
                        problems.add("Table " + table.name + ": a row differs (expected count "
                                + entry.getValue() + ", actual " + ac + ")");
                    }
                }
                rowIndex++;
            }
            for (Map.Entry<String, Integer> entry : actual.entrySet()) {
                if (!expected.containsKey(entry.getKey())) {
                    mismatched += entry.getValue();
                    if (mismatched <= 10) {
                        problems.add("Table " + table.name + ": target has an unexpected row");
                    }
                }
            }
        }
        return new TableDiff(mismatched, problems);
    }

    private Object normalizeJdbcValue(Object value) {
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toString().replace('T', ' ');
        }
        if (value instanceof java.sql.Date d) {
            return d.toString();
        }
        if (value instanceof java.sql.Time t) {
            return t.toString();
        }
        if (value instanceof java.sql.Blob blob) {
            try {
                return blob.getBytes(1, (int) blob.length());
            } catch (SQLException e) {
                return new byte[0];
            }
        }
        return value;
    }

    private record TableDiff(long mismatchedRows, List<String> problems) {
    }
}
