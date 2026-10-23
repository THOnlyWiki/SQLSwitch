package cc.thonly.sqlswitch.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A parsed SQL column type.  The original declaration is kept in {@link #raw}
 * so that same-family conversions do not lose information.
 */
public final class DataType {
    public String raw = "";
    public String base = "";
    public TypeFamily family = TypeFamily.UNKNOWN;
    public Integer length;
    public Integer precision;
    public Integer scale;
    public boolean unsigned;
    public boolean zerofill;
    public boolean binaryAttribute;
    public List<String> enumValues = List.of();

    public DataType() {
    }

    public DataType(TypeFamily family, String base, Integer length) {
        this.family = family;
        this.base = base;
        this.length = length;
        this.raw = base + (length == null ? "" : "(" + length + ")");
    }

    public boolean isPresent() {
        return raw != null && !raw.isBlank();
    }

    public int lengthOr(int fallback) {
        return length == null || length <= 0 ? fallback : length;
    }

    public int precisionOr(int fallback) {
        return precision == null || precision <= 0 ? fallback : precision;
    }

    public int scaleOr(int fallback) {
        return scale == null || scale < 0 ? fallback : scale;
    }

    public DataType copy() {
        DataType t = new DataType();
        t.raw = raw;
        t.base = base;
        t.family = family;
        t.length = length;
        t.precision = precision;
        t.scale = scale;
        t.unsigned = unsigned;
        t.zerofill = zerofill;
        t.binaryAttribute = binaryAttribute;
        t.enumValues = List.copyOf(enumValues);
        return t;
    }

    public void withLength(int newLength) {
        this.length = newLength;
        this.raw = base + "(" + newLength + ")";
    }

    public static DataType parse(String declaration) {
        DataType type = new DataType();
        if (declaration == null) {
            return type;
        }
        String raw = declaration.trim().replaceAll("\\s+", " ");
        type.raw = raw;
        if (raw.isEmpty()) {
            return type;
        }

        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.endsWith(" UNSIGNED")) {
            type.unsigned = true;
            raw = raw.substring(0, raw.length() - " UNSIGNED".length()).trim();
            upper = raw.toUpperCase(Locale.ROOT);
        }
        if (upper.endsWith(" ZEROFILL")) {
            type.zerofill = true;
            raw = raw.substring(0, raw.length() - " ZEROFILL".length()).trim();
            upper = raw.toUpperCase(Locale.ROOT);
        }

        String args = null;
        int open = raw.indexOf('(');
        if (open >= 0) {
            int close = raw.lastIndexOf(')');
            if (close > open) {
                args = raw.substring(open + 1, close).trim();
                raw = (raw.substring(0, open) + " " + raw.substring(close + 1)).trim();
            }
        }
        String base = raw.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
        base = base.replaceAll("\\bUNSIGNED\\b", "").replaceAll("\\bSIGNED\\b", "")
                .replaceAll("\\bZEROFILL\\b", "").replaceAll("\\s+", " ").trim();
        type.base = base;

        switch (base) {
            case "BOOL", "BOOLEAN" -> type.family = TypeFamily.BOOLEAN;
            case "TINYINT", "INT1", "TINYINT1" -> type.family = TypeFamily.INTEGER;
            case "SMALLINT", "INT2" -> type.family = TypeFamily.INTEGER;
            case "MEDIUMINT", "MIDDLEINT", "INT3" -> type.family = TypeFamily.INTEGER;
            case "INT", "INTEGER", "INT4", "SERIAL" -> type.family = TypeFamily.INTEGER;
            case "BIGINT", "INT8", "BIGSERIAL", "BIG INT", "UNSIGNED BIG INT" -> type.family = TypeFamily.INTEGER;
            case "SMALL INT" -> type.family = TypeFamily.INTEGER;
            case "TINY INT" -> type.family = TypeFamily.INTEGER;
            case "MEDIUM INT" -> type.family = TypeFamily.INTEGER;
            case "DECIMAL", "NUMERIC", "DEC", "FIXED" -> type.family = TypeFamily.DECIMAL;
            case "FLOAT" -> type.family = TypeFamily.FLOAT;
            case "DOUBLE", "DOUBLE PRECISION", "REAL" -> type.family = TypeFamily.DOUBLE;
            case "CHAR", "CHARACTER", "NCHAR", "NATIONAL CHAR", "NATIONAL CHARACTER" -> type.family = TypeFamily.CHAR;
            case "VARCHAR", "CHARACTER VARYING", "VARYING CHARACTER", "NVARCHAR",
                    "NATIONAL VARCHAR", "NATIONAL CHARACTER VARYING" -> type.family = TypeFamily.VARCHAR;
            case "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB", "NCLOB", "LONG VARCHAR", "LONG" ->
                    type.family = TypeFamily.TEXT;
            case "BINARY" -> type.family = TypeFamily.BINARY;
            case "VARBINARY" -> type.family = TypeFamily.VARBINARY;
            case "BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB" -> type.family = TypeFamily.BLOB;
            case "DATE" -> type.family = TypeFamily.DATE;
            case "TIME" -> type.family = TypeFamily.TIME;
            case "DATETIME" -> type.family = TypeFamily.DATETIME;
            case "TIMESTAMP" -> type.family = TypeFamily.TIMESTAMP;
            case "YEAR" -> type.family = TypeFamily.YEAR;
            case "JSON", "JSONB" -> type.family = TypeFamily.JSON;
            case "ENUM" -> type.family = TypeFamily.ENUM;
            case "SET" -> type.family = TypeFamily.SET;
            case "BIT" -> type.family = TypeFamily.BIT;
            case "UUID" -> type.family = TypeFamily.UUID;
            case "INET", "INET4", "INET6", "CIDR", "MACADDR" -> type.family = TypeFamily.INET;
            case "GEOMETRY", "POINT", "LINESTRING", "POLYGON", "MULTIPOINT",
                    "MULTILINESTRING", "MULTIPOLYGON", "GEOMETRYCOLLECTION" ->
                    type.family = TypeFamily.GEOMETRY;
            default -> {
                if (base.contains("INT")) {
                    type.family = TypeFamily.INTEGER;
                } else if (base.contains("CHAR") || base.contains("CLOB") || base.contains("TEXT")) {
                    type.family = TypeFamily.TEXT;
                } else if (base.contains("BLOB")) {
                    type.family = TypeFamily.BLOB;
                } else if (base.contains("REAL") || base.contains("FLOA") || base.contains("DOUB")) {
                    type.family = TypeFamily.DOUBLE;
                } else {
                    type.family = TypeFamily.UNKNOWN;
                }
            }
        }

        if (args != null && !args.isBlank()) {
            if (type.family == TypeFamily.ENUM || type.family == TypeFamily.SET) {
                type.enumValues = parseEnumValues(args);
            } else {
                String[] parts = args.split(",");
                if (parts.length >= 2) {
                    try {
                        type.precision = Integer.parseInt(parts[0].trim());
                        type.scale = Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException ignored) {
                        // Some vendors put non-numeric modifiers here; ignore.
                    }
                    type.length = type.precision;
                } else {
                    try {
                        type.length = Integer.parseInt(parts[0].trim());
                    } catch (NumberFormatException ignored) {
                        // ignore
                    }
                }
            }
        }

        if (type.family == TypeFamily.INTEGER && type.base.equals("TINYINT") && type.length != null
                && type.length == 1) {
            // TINYINT(1) is the conventional MySQL boolean.  Keep the family as
            // INTEGER; writers may choose a boolean presentation.
        }
        return type;
    }

    private static List<String> parseEnumValues(String args) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean hasCurrent = false;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (quoted) {
                if (c == '\'' && i + 1 < args.length() && args.charAt(i + 1) == '\'') {
                    current.append('\'');
                    i++;
                } else if (c == '\\' && i + 1 < args.length()) {
                    current.append(args.charAt(++i));
                } else if (c == '\'') {
                    quoted = false;
                } else {
                    current.append(c);
                }
            } else if (c == '\'') {
                quoted = true;
                hasCurrent = true;
            } else if (c == ',') {
                if (hasCurrent) {
                    values.add(current.toString());
                }
                current.setLength(0);
                hasCurrent = false;
            } else if (!Character.isWhitespace(c)) {
                current.append(c);
                hasCurrent = true;
            }
        }
        if (hasCurrent) {
            values.add(current.toString());
        }
        return Collections.unmodifiableList(values);
    }

    @Override
    public String toString() {
        return raw;
    }
}
