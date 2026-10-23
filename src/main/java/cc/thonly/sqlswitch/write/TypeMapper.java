package cc.thonly.sqlswitch.write;

import cc.thonly.sqlswitch.infer.ColumnStats;
import cc.thonly.sqlswitch.model.Column;
import cc.thonly.sqlswitch.model.DataType;
import cc.thonly.sqlswitch.model.TargetConfig;
import cc.thonly.sqlswitch.model.TypeFamily;
import cc.thonly.sqlswitch.util.SqlEscaper;

import java.util.List;

/**
 * Maps the target-neutral type model to MySQL/MariaDB and SQLite declarations.
 */
public final class TypeMapper {
    private TypeMapper() {
    }

    public static String mysqlType(Column column, ColumnStats stats, TargetConfig config) {
        DataType type = column.type;
        if (type == null || !type.isPresent() || type.family == TypeFamily.UNKNOWN) {
            return config.mapTextToLongText ? "LONGTEXT" : "TEXT";
        }
        switch (type.family) {
            case BOOLEAN:
                return "TINYINT(1)";
            case INTEGER: {
                String base = integerBase(type, stats);
                String sql = base;
                if (type.unsigned) {
                    sql += " UNSIGNED";
                }
                return sql;
            }
            case DECIMAL: {
                int precision = type.precision == null ? 38 : Math.min(65, Math.max(1, type.precision));
                int scale = type.scale == null ? 10 : Math.max(0, Math.min(30, type.scale));
                if (scale > precision) {
                    precision = scale;
                }
                return "DECIMAL(" + precision + "," + scale + ")";
            }
            case FLOAT:
                return "FLOAT";
            case DOUBLE:
                return "DOUBLE";
            case CHAR: {
                int length = type.lengthOr(1);
                if (length > 16383) {
                    return "LONGTEXT";
                }
                if (length > 255) {
                    return "VARCHAR(" + length + ")";
                }
                return "CHAR(" + length + ")";
            }
            case VARCHAR: {
                int length = type.lengthOr(255);
                if (length > 16383) {
                    return "LONGTEXT";
                }
                return "VARCHAR(" + length + ")";
            }
            case TEXT:
                return config.mapTextToLongText ? "LONGTEXT" : "TEXT";
            case BINARY: {
                int length = type.lengthOr(1);
                return "BINARY(" + Math.min(length, 255) + ")";
            }
            case VARBINARY: {
                int length = type.lengthOr(255);
                if (length > 65535) {
                    return "LONGBLOB";
                }
                return "VARBINARY(" + length + ")";
            }
            case BLOB:
                return "LONGBLOB";
            case DATE:
                return "DATE";
            case TIME:
                return type.length != null && type.length <= 6 ? "TIME(" + type.length + ")" : "TIME";
            case DATETIME:
                return type.length != null && type.length <= 6 ? "DATETIME(" + type.length + ")" : "DATETIME";
            case TIMESTAMP:
                return type.length != null && type.length <= 6 ? "TIMESTAMP(" + type.length + ")" : "TIMESTAMP";
            case YEAR:
                return "YEAR";
            case JSON:
                return config.useJsonType && (config.isMySql() || config.versionAtLeast("10.2.1"))
                        ? "JSON" : "LONGTEXT";
            case ENUM:
                return type.enumValues.isEmpty() ? "VARCHAR(255)" : "ENUM(" + enumValues(type) + ")";
            case SET:
                return type.enumValues.isEmpty() ? "LONGTEXT" : "SET(" + enumValues(type) + ")";
            case BIT: {
                int length = type.lengthOr(1);
                if (length > 64) {
                    return "VARBINARY(" + ((length + 7) / 8) + ")";
                }
                return "BIT(" + length + ")";
            }
            case UUID:
                return "CHAR(36)";
            case INET:
                return "VARCHAR(45)";
            case GEOMETRY:
                return "GEOMETRY";
            default:
                return config.mapTextToLongText ? "LONGTEXT" : "TEXT";
        }
    }

    private static String integerBase(DataType type, ColumnStats stats) {
        String base = type.base == null ? "" : type.base.toUpperCase();
        if (base.equals("TINYINT") || base.equals("INT1")) {
            return "TINYINT";
        }
        if (base.equals("SMALLINT") || base.equals("INT2")) {
            return "SMALLINT";
        }
        if (base.equals("MEDIUMINT") || base.equals("INT3")) {
            return "MEDIUMINT";
        }
        if (base.equals("BIGINT") || base.equals("INT8")) {
            return "BIGINT";
        }
        if (stats != null && stats.minNumber != null && stats.maxNumber != null) {
            if (stats.minNumber.compareTo(java.math.BigDecimal.valueOf(Integer.MIN_VALUE)) < 0
                    || stats.maxNumber.compareTo(java.math.BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                return "BIGINT";
            }
        }
        return "INT";
    }

    private static String enumValues(DataType type) {
        List<String> values = type.enumValues;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(SqlEscaper.mysqlStringLiteral(values.get(i)));
        }
        return sb.toString();
    }

    public static String sqliteType(Column column, boolean strict) {
        DataType type = column.type;
        if (type == null || !type.isPresent() || type.family == TypeFamily.UNKNOWN) {
            return "TEXT";
        }
        if (strict) {
            return switch (type.family) {
                case BOOLEAN, INTEGER, BIT, YEAR -> "INTEGER";
                case DECIMAL, FLOAT, DOUBLE -> "REAL";
                case BINARY, VARBINARY, BLOB, GEOMETRY -> "BLOB";
                default -> "TEXT";
            };
        }
        return switch (type.family) {
            case BOOLEAN, INTEGER, BIT, YEAR -> "INTEGER";
            case DECIMAL -> type.scale != null
                    ? "DECIMAL(" + type.precisionOr(38) + "," + type.scale + ")" : "NUMERIC";
            case FLOAT, DOUBLE -> "REAL";
            case CHAR, VARCHAR, TEXT, JSON, ENUM, SET, UUID, INET -> "TEXT";
            case BINARY, VARBINARY, BLOB, GEOMETRY -> "BLOB";
            case DATE -> "DATE";
            case TIME -> "TIME";
            case DATETIME -> "DATETIME";
            case TIMESTAMP -> "TIMESTAMP";
            default -> "TEXT";
        };
    }

    public static boolean isTextual(Column column) {
        return column.type != null && column.type.family.isTextual();
    }
}

