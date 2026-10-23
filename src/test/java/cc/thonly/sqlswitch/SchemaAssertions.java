package cc.thonly.sqlswitch;

import cc.thonly.sqlswitch.model.Column;
import cc.thonly.sqlswitch.model.Row;
import cc.thonly.sqlswitch.model.Schema;
import cc.thonly.sqlswitch.model.Table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class SchemaAssertions {
    private SchemaAssertions() {
    }

    static void assertSameContent(Schema expected, Schema actual, boolean compareSequences) {
        assertEquals(expected.tables.size(), actual.tables.size(),
                "table count; expected " + expected.tables + " actual " + actual.tables);
        for (Table expectedTable : expected.tables) {
            Table actualTable = actual.getTable(expectedTable.name);
            assertNotNull(actualTable, "missing table " + expectedTable.name);
            List<String> expectedColumns = expectedTable.columns.stream().map(c -> c.name).toList();
            List<String> actualColumns = actualTable.columns.stream().map(c -> c.name).toList();
            assertEquals(expectedColumns, actualColumns, "columns of " + expectedTable.name);
            assertEquals(canonicalRows(expectedTable), canonicalRows(actualTable),
                    "rows of " + expectedTable.name);
        }
        if (compareSequences) {
            assertEquals(expected.sequences, actual.sequences, "sqlite_sequence");
        }
    }

    static Map<String, Integer> canonicalRows(Table table) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < table.columns.size(); i++) {
            Column column = table.columns.get(i);
            if (!column.isGenerated()) {
                indexes.add(i);
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Row row : table.rows) {
            StringBuilder key = new StringBuilder();
            for (int index : indexes) {
                key.append(row.present[index] ? row.values[index].canonical() : "ABSENT")
                        .append('\u0001');
            }
            counts.merge(key.toString(), 1, Integer::sum);
        }
        return counts;
    }

    static void assertAutoIncrementValues(Schema expected, Schema actual) {
        for (Table expectedTable : expected.tables) {
            Table actualTable = actual.getTable(expectedTable.name);
            assertNotNull(actualTable);
            long e = Math.max(1, expectedTable.autoIncrementValue);
            long a = Math.max(1, actualTable.autoIncrementValue);
            assertEquals(e, a, "AUTO_INCREMENT value of " + expectedTable.name);
        }
    }
}
