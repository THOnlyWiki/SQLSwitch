package cc.thonly.sqlswitch.model;

/**
 * A key part in an index.  MySQL allows prefix lengths; SQLite does not, so
 * the SQLite writer expands prefixes to full columns and records a warning.
 */
public record IndexColumn(String column, Integer prefixLength, boolean descending) {
    public static IndexColumn of(String column) {
        return new IndexColumn(column, null, false);
    }

    public String display() {
        return column + (prefixLength == null ? "" : "(" + prefixLength + ")")
                + (descending ? " DESC" : "");
    }
}
