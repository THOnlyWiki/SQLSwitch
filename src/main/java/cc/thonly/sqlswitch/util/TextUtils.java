package cc.thonly.sqlswitch.util;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class TextUtils {
    private TextUtils() {
    }

    public static int utf8Length(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    public static boolean hasNonBmp(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isHighSurrogate(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasControlChars(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || (c < 0x20 && c != '\n' && c != '\r' && c != '\t')) {
                return true;
            }
        }
        return false;
    }

    public static String unquoteIdentifier(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`')) {
                return v.substring(1, v.length() - 1)
                        .replace(String.valueOf(first) + first, String.valueOf(first));
            }
            if (first == '[' && last == ']') {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    public static String stripSchema(String name) {
        if (name == null) {
            return null;
        }
        String v = unquoteIdentifier(name);
        int dot = v.lastIndexOf('.');
        if (dot >= 0) {
            return unquoteIdentifier(v.substring(dot + 1));
        }
        return v;
    }

    public static String sanitizeIdentifier(String raw, int maxLength) {
        if (raw == null || raw.isBlank()) {
            return "x";
        }
        String s = raw.trim();
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String result = sb.toString();
        if (result.isEmpty()) {
            result = "x";
        }
        if (!Character.isLetter(result.charAt(0)) && result.charAt(0) != '_') {
            result = "x_" + result;
        }
        if (result.length() > maxLength) {
            result = result.substring(0, maxLength);
        }
        return result;
    }

    public static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    public static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    public static String firstLine(String value) {
        if (value == null) {
            return "";
        }
        int nl = value.indexOf('\n');
        return nl < 0 ? value : value.substring(0, nl);
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / 1024.0 / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f GiB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    public static String padRight(String value, int width) {
        if (value == null) {
            value = "";
        }
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }

    public static String joinQuoted(Iterable<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        return sb.toString();
    }
}
