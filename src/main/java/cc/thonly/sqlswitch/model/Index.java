package cc.thonly.sqlswitch.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A secondary index or unique constraint.
 */
public final class Index {
    public String name;
    public boolean unique;
    public String type = "BTREE";
    public List<IndexColumn> columns = new ArrayList<>();
    public String where;
    public String origin = "INDEX";
    public String comment;
    public String unsupportedReason;

    public Index() {
    }

    public Index(String name, boolean unique, List<IndexColumn> columns) {
        this.name = name;
        this.unique = unique;
        this.columns = new ArrayList<>(columns);
    }

    public boolean isFullText() {
        return "FULLTEXT".equalsIgnoreCase(type);
    }

    public boolean isSpatial() {
        return "SPATIAL".equalsIgnoreCase(type);
    }

    public String columnSignature() {
        StringBuilder sb = new StringBuilder();
        for (IndexColumn c : columns) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(c.column().toLowerCase());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return (unique ? "UNIQUE " : "") + "INDEX " + name + " (" + columns + ")";
    }
}
