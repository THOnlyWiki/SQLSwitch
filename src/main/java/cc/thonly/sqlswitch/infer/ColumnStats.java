package cc.thonly.sqlswitch.infer;

import cc.thonly.sqlswitch.model.Column;
import cc.thonly.sqlswitch.model.Table;
import cc.thonly.sqlswitch.model.Value;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cached statistics for one column, gathered from the in-memory rows.
 */
public final class ColumnStats {
    private static final Pattern DATE_ONLY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern SQL_DATETIME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?");
    private static final Pattern ISO_TIMESTAMP = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?");
    private static final Set<String> BOOLEAN_NAMES = Set.of(
            "enabled", "disabled", "deleted", "hidden", "visible", "read", "unread",
            "minor", "bot", "confirmed", "verified", "active", "published", "locked",
            "protected", "successful", "transcludable", "local", "need_render",
            "hard_delete", "ai_assisted", "is_bot", "is_minor", "is_deleted",
            "prevent_email", "prevent_create", "anon_only", "email_copy",
            "signature_wikitext", "email_confirmed", "email_on_password_reset",
            "email_allow_others", "email_allow_new_users", "deleted_flag"
    );

    public int present;
    public int nulls;
    public int maxTextLength;
    public int maxBytesLength;
    public boolean hasNonBmp;
    public boolean hasNul;
    public boolean hasControl;
    public boolean allStrings;
    public boolean allBytes;
    public boolean allNumbers;
    public boolean allIntegers;
    public boolean booleanValues = true;
    public boolean allDateOnly = true;
    public boolean allSqlDateTime = true;
    public boolean hasIsoTimestamp;
    public BigDecimal minNumber;
    public BigDecimal maxNumber;

    public static ColumnStats of(Table table, Column column) {
        ColumnStats stats = new ColumnStats();
        stats.allStrings = true;
        stats.allBytes = true;
        stats.allNumbers = true;
        stats.allIntegers = true;
        int index = table.columns.indexOf(column);
        for (var row : table.rows) {
            if (!row.present[index]) {
                continue;
            }
            Value value = row.values[index];
            if (value == null || value.kind == Value.Kind.NULL) {
                stats.nulls++;
                continue;
            }
            stats.present++;
            switch (value.kind) {
                case STRING -> {
                    stats.allBytes = false;
                    stats.allNumbers = false;
                    stats.allIntegers = false;
                    int len = value.string.length();
                    stats.maxTextLength = Math.max(stats.maxTextLength, len);
                    if (containsNonBmp(value.string)) {
                        stats.hasNonBmp = true;
                    }
                    if (value.string.indexOf('\0') >= 0) {
                        stats.hasNul = true;
                    }
                    if (containsControl(value.string)) {
                        stats.hasControl = true;
                    }
                    if (!DATE_ONLY.matcher(value.string).matches()) {
                        stats.allDateOnly = false;
                    }
                    if (!SQL_DATETIME.matcher(value.string).matches()) {
                        stats.allSqlDateTime = false;
                    }
                    if (ISO_TIMESTAMP.matcher(value.string).matches()) {
                        stats.hasIsoTimestamp = true;
                    }
                }
                case BYTES -> {
                    stats.allStrings = false;
                    stats.allNumbers = false;
                    stats.allIntegers = false;
                    stats.maxBytesLength = Math.max(stats.maxBytesLength, value.bytes.length);
                }
                case NUMBER -> {
                    stats.allStrings = false;
                    stats.allBytes = false;
                    BigDecimal n = value.number == null ? BigDecimal.ZERO : value.number;
                    if (stats.minNumber == null || n.compareTo(stats.minNumber) < 0) {
                        stats.minNumber = n;
                    }
                    if (stats.maxNumber == null || n.compareTo(stats.maxNumber) > 0) {
                        stats.maxNumber = n;
                    }
                    if (n.stripTrailingZeros().scale() > 0) {
                        stats.allIntegers = false;
                    }
                    if (n.compareTo(BigDecimal.ZERO) != 0 && n.compareTo(BigDecimal.ONE) != 0) {
                        stats.booleanValues = false;
                    }
                }
                case BOOLEAN -> {
                    stats.allStrings = false;
                    stats.allBytes = false;
                    if ("0".equals(value.expression) || "1".equals(value.expression)) {
                        // boolean-like
                    } else {
                        stats.booleanValues = false;
                    }
                }
                default -> {
                    stats.allStrings = false;
                    stats.allBytes = false;
                    stats.allNumbers = false;
                    stats.allIntegers = false;
                    stats.booleanValues = false;
                }
            }
        }
        if (stats.present == 0) {
            stats.allStrings = false;
            stats.allBytes = false;
            stats.allNumbers = false;
            stats.allIntegers = false;
            stats.booleanValues = false;
            stats.allDateOnly = false;
            stats.allSqlDateTime = false;
        }
        return stats;
    }

    public boolean isBooleanLike() {
        return present > 0 && booleanValues;
    }

    public static boolean nameLooksBoolean(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (BOOLEAN_NAMES.contains(n)) {
            return true;
        }
        return n.startsWith("is_") || n.startsWith("has_") || n.startsWith("allow_")
                || n.startsWith("enable_") || n.startsWith("disable_") || n.endsWith("_flag");
    }

    private static boolean containsNonBmp(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isHighSurrogate(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || (c < 0x20 && c != '\n' && c != '\r' && c != '\t')) {
                return true;
            }
        }
        return false;
    }
}
