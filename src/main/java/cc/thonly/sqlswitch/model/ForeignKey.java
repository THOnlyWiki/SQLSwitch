package cc.thonly.sqlswitch.model;

import java.util.List;

/**
 * A table-level foreign key.
 */
public final class ForeignKey {
    public String name;
    public List<String> columns = List.of();
    public String referencedTable;
    public List<String> referencedColumns = List.of();
    public String onDelete;
    public String onUpdate;
    public String match;

    public ForeignKey() {
    }

    public ForeignKey(String name, List<String> columns, String referencedTable,
                      List<String> referencedColumns, String onDelete, String onUpdate) {
        this.name = name;
        this.columns = List.copyOf(columns);
        this.referencedTable = referencedTable;
        this.referencedColumns = List.copyOf(referencedColumns);
        this.onDelete = onDelete;
        this.onUpdate = onUpdate;
    }

    @Override
    public String toString() {
        return "FOREIGN KEY " + columns + " REFERENCES " + referencedTable + referencedColumns;
    }
}
