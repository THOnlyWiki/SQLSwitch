package cc.thonly.sqlswitch.infer;

import cc.thonly.sqlswitch.model.Column;
import cc.thonly.sqlswitch.model.DataType;
import cc.thonly.sqlswitch.model.Schema;
import cc.thonly.sqlswitch.model.Table;
import cc.thonly.sqlswitch.model.TypeFamily;
import cc.thonly.sqlswitch.util.Log;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Optional type refinement.  The converter works from declared types first;
 * this pass is mainly for SQLite tables whose columns have no type at all,
 * plus safe widening when a dump's VARCHAR is too small for its data.
 */
public final class TypeInference {
    public enum Mode {
        OFF,
        MISSING,
        ALL
    }

    private TypeInference() {
    }

    public static void apply(Schema schema, Mode mode, boolean inferDates, boolean inferBooleans,
                             boolean autoWiden) {
        if (mode == null) {
            mode = Mode.MISSING;
        }
        for (Table table : schema.tables) {
            for (Column column : table.columns) {
                if (column.isGenerated()) {
                    continue;
                }
                ColumnStats stats = ColumnStats.of(table, column);
                boolean missing = column.type == null || !column.type.isPresent()
                        || column.type.family == TypeFamily.UNKNOWN;
                if (missing && mode != Mode.OFF) {
                    DataType inferred = inferType(column.name, stats, inferDates, inferBooleans);
                    column.type = inferred;
                    Log.detail("Inferred type " + inferred.raw + " for " + table.name + "." + column.name);
                } else if (!missing && mode == Mode.ALL) {
                    if (inferBooleans && column.type.family == TypeFamily.INTEGER
                            && ColumnStats.nameLooksBoolean(column.name) && stats.isBooleanLike()) {
                        column.type = new DataType(TypeFamily.BOOLEAN, "BOOLEAN", null);
                    }
                    if (inferDates && column.type.isPresent() && column.type.family == TypeFamily.TEXT
                            && stats.present > 0 && stats.allStrings) {
                        DataType temporal = inferTemporal(column.name, stats);
                        if (temporal != null) {
                            column.type = temporal;
                        }
                    }
                }
                if (autoWiden) {
                    widen(table.name, column, stats);
                }
            }
        }
    }

    private static DataType inferType(String name, ColumnStats stats, boolean inferDates,
                                      boolean inferBooleans) {
        if (stats.present == 0) {
            return new DataType(TypeFamily.TEXT, "TEXT", null);
        }
        if (inferBooleans && ColumnStats.nameLooksBoolean(name) && stats.isBooleanLike()) {
            return new DataType(TypeFamily.BOOLEAN, "BOOLEAN", null);
        }
        if (stats.allBytes) {
            return new DataType(TypeFamily.BLOB, "BLOB", null);
        }
        if (stats.allNumbers) {
            if (!stats.allIntegers) {
                int[] ps = decimalPrecisionScale(stats);
                if (ps != null) {
                    DataType type = new DataType(TypeFamily.DECIMAL, "DECIMAL", ps[0]);
                    type.precision = ps[0];
                    type.scale = ps[1];
                    type.raw = "DECIMAL(" + ps[0] + "," + ps[1] + ")";
                    return type;
                }
                return new DataType(TypeFamily.DOUBLE, "DOUBLE", null);
            }
            return integerType(stats);
        }
        if (stats.allStrings) {
            if (inferDates) {
                DataType temporal = inferTemporal(name, stats);
                if (temporal != null) {
                    return temporal;
                }
            }
            int len = Math.max(1, stats.maxTextLength);
            if (len <= 16383) {
                return new DataType(TypeFamily.VARCHAR, "VARCHAR", len);
            }
            return new DataType(TypeFamily.TEXT, "TEXT", null);
        }
        return new DataType(TypeFamily.TEXT, "TEXT", null);
    }

    private static DataType integerType(ColumnStats stats) {
        BigDecimal min = stats.minNumber == null ? BigDecimal.ZERO : stats.minNumber;
        BigDecimal max = stats.maxNumber == null ? BigDecimal.ZERO : stats.maxNumber;
        if (min.compareTo(BigDecimal.valueOf(-128)) >= 0 && max.compareTo(BigDecimal.valueOf(127)) <= 0) {
            return new DataType(TypeFamily.INTEGER, "TINYINT", null);
        }
        if (min.compareTo(BigDecimal.valueOf(-32768)) >= 0 && max.compareTo(BigDecimal.valueOf(32767)) <= 0) {
            return new DataType(TypeFamily.INTEGER, "SMALLINT", null);
        }
        if (min.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) >= 0
                && max.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0) {
            return new DataType(TypeFamily.INTEGER, "INT", null);
        }
        return new DataType(TypeFamily.INTEGER, "BIGINT", null);
    }

    private static int[] decimalPrecisionScale(ColumnStats stats) {
        if (stats.minNumber == null || stats.maxNumber == null) {
            return null;
        }
        int scale = Math.max(stats.minNumber.scale(), stats.maxNumber.scale());
        scale = Math.max(0, Math.min(scale, 30));
        BigDecimal maxAbs = stats.minNumber.abs().max(stats.maxNumber.abs());
        int integerDigits = maxAbs.setScale(0, java.math.RoundingMode.DOWN).toPlainString().length();
        int precision = Math.min(65, integerDigits + scale);
        if (precision > 65) {
            return null;
        }
        return new int[]{Math.max(precision, 1), scale};
    }

    private static DataType inferTemporal(String name, ColumnStats stats) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (!(lower.contains("date") || lower.contains("time") || lower.endsWith("_at"))) {
            return null;
        }
        if (stats.allDateOnly) {
            return new DataType(TypeFamily.DATE, "DATE", null);
        }
        // Do not infer DATETIME for SQLite's ISO-8601 'T' + nanosecond strings;
        // MySQL would truncate or reject them.  Only plain SQL datetime text is
        // safe to convert.
        if (stats.allSqlDateTime && !stats.hasIsoTimestamp) {
            return new DataType(TypeFamily.DATETIME, "DATETIME", null);
        }
        return null;
    }

    private static void widen(String tableName, Column column, ColumnStats stats) {
        DataType type = column.type;
        if (type == null || !type.isPresent() || stats.present == 0) {
            return;
        }
        if ((type.family == TypeFamily.VARCHAR || type.family == TypeFamily.CHAR)
                && type.length != null && stats.maxTextLength > type.length) {
            int newLength = stats.maxTextLength;
            if (newLength <= 16383) {
                type.withLength(newLength);
                Log.detail("Widened " + tableName + "." + column.name + " to VARCHAR(" + newLength + ")");
            } else {
                type.family = TypeFamily.TEXT;
                type.base = "TEXT";
                type.raw = "TEXT";
                type.length = null;
                Log.detail("Promoted " + tableName + "." + column.name + " to TEXT (max length "
                        + newLength + ")");
            }
        } else if (type.family == TypeFamily.INTEGER && stats.allNumbers && !stats.allIntegers) {
            // An INTEGER column with fractional data cannot stay integer.
            int[] ps = decimalPrecisionScale(stats);
            if (ps != null) {
                type.family = TypeFamily.DECIMAL;
                type.base = "DECIMAL";
                type.precision = ps[0];
                type.scale = ps[1];
                type.raw = "DECIMAL(" + ps[0] + "," + ps[1] + ")";
            }
        } else if (type.family == TypeFamily.DECIMAL) {
            int[] ps = decimalPrecisionScale(stats);
            if (ps != null && (type.precisionOr(0) < ps[0] || type.scaleOr(0) < ps[1])) {
                type.precision = ps[0];
                type.scale = ps[1];
                type.raw = "DECIMAL(" + ps[0] + "," + ps[1] + ")";
                Log.detail("Widened " + tableName + "." + column.name + " to " + type.raw);
            }
        }
    }
}
