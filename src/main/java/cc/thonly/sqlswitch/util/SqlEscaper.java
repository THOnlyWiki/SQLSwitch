package cc.thonly.sqlswitch.util;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Escaping rules for the two output families.  These are intentionally
 * different: SQLite only understands doubled quotes, while MySQL treats a
 * backslash as an escape character (unless NO_BACKSLASH_ESCAPES is enabled).
 */
public final class SqlEscaper {
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private SqlEscaper() {
    }

    public static String mysqlIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    public static String sqliteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public static String mysqlStringLiteral(String value) {
        return "'" + escapeMySql(value) + "'";
    }

    public static String escapeMySql(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case 0 -> sb.append("\\0");
                case '\b' -> sb.append("\\b");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case 0x1A -> sb.append("\\Z");
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '"' -> sb.append("\\\"");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String sqliteStringLiteral(String value) {
        if (value == null) {
            return "NULL";
        }
        if (value.indexOf('\0') >= 0) {
            return "CAST(X'" + HEX.formatHex(value.getBytes(StandardCharsets.UTF_8)) + "' AS TEXT)";
        }
        return "'" + value.replace("'", "''") + "'";
    }

    public static String mysqlBytesLiteral(byte[] bytes) {
        return "X'" + HEX.formatHex(bytes) + "'";
    }

    public static String sqliteBytesLiteral(byte[] bytes) {
        return "X'" + HEX.formatHex(bytes) + "'";
    }

    public static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    public static boolean needsSqliteHexCast(String value) {
        return value != null && value.indexOf('\0') >= 0;
    }
}
