package cc.thonly.sqlswitch.model;

import java.util.Arrays;

/**
 * One data row.  The array has the full table width; {@code present[i]} says
 * whether the row explicitly supplied column i in the source INSERT statement.
 * That matters for defaults: an omitted column must not be written as NULL.
 */
public final class Row {
    public final Value[] values;
    public final boolean[] present;

    public Row(Value[] values, boolean[] present) {
        if (values.length != present.length) {
            throw new IllegalArgumentException("values/present length mismatch");
        }
        this.values = values;
        this.present = present;
    }

    public static Row full(Value[] values) {
        boolean[] present = new boolean[values.length];
        Arrays.fill(present, true);
        return new Row(values, present);
    }

    public Value value(int index) {
        return values[index];
    }

    public boolean isPresent(int index) {
        return present[index];
    }

    public int width() {
        return values.length;
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }
}
