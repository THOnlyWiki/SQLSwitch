package cc.thonly.sqlswitch.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A single SQL value.  The conversion pipeline keeps values typed instead of
 * stringly-typed so that escaping and type mapping can be exact.
 */
public final class Value {
    public enum Kind {
        NULL,
        STRING,
        BYTES,
        NUMBER,
        BOOLEAN,
        DEFAULT,
        EXPRESSION
    }

    public static final Value NULL = new Value(Kind.NULL, null, null, null, null);
    public static final Value DEFAULT = new Value(Kind.DEFAULT, null, null, null, null);

    public final Kind kind;
    public final String string;
    public final byte[] bytes;
    public final BigDecimal number;
    public final String expression;

    private Value(Kind kind, String string, byte[] bytes, BigDecimal number, String expression) {
        this.kind = kind;
        this.string = string;
        this.bytes = bytes;
        this.number = number;
        this.expression = expression;
    }

    public static Value string(String value) {
        return value == null ? NULL : new Value(Kind.STRING, value, null, null, null);
    }

    public static Value bytes(byte[] value) {
        return value == null ? NULL : new Value(Kind.BYTES, null, value.clone(), null, null);
    }

    public static Value number(BigDecimal value) {
        return value == null ? NULL : new Value(Kind.NUMBER, null, null, value, null);
    }

    public static Value number(long value) {
        return number(BigDecimal.valueOf(value));
    }

    public static Value number(BigInteger value) {
        return value == null ? NULL : number(new BigDecimal(value));
    }

    public static Value bool(boolean value) {
        return new Value(Kind.BOOLEAN, null, null, null, value ? "1" : "0");
    }

    public static Value expression(String raw) {
        return raw == null ? NULL : new Value(Kind.EXPRESSION, null, null, null, raw);
    }

    public static Value fromJava(Object value) {
        if (value == null) {
            return NULL;
        }
        if (value instanceof byte[] b) {
            return bytes(b);
        }
        if (value instanceof Boolean b) {
            return bool(b);
        }
        if (value instanceof BigDecimal d) {
            return number(d);
        }
        if (value instanceof BigInteger i) {
            return number(i);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            return number(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return string(String.valueOf(d));
            }
            return number(BigDecimal.valueOf(d));
        }
        if (value instanceof String s) {
            return string(s);
        }
        if (value instanceof Timestamp ts) {
            return string(ts.toLocalDateTime().toString().replace('T', ' '));
        }
        if (value instanceof LocalDateTime dt) {
            return string(dt.toString().replace('T', ' '));
        }
        if (value instanceof LocalDate d) {
            return string(d.toString());
        }
        if (value instanceof LocalTime t) {
            return string(t.toString());
        }
        if (value instanceof OffsetDateTime odt) {
            return string(odt.toString());
        }
        if (value instanceof java.sql.Date d) {
            return string(d.toString());
        }
        if (value instanceof java.sql.Time t) {
            return string(t.toString());
        }
        return string(String.valueOf(value));
    }

    public boolean isNull() {
        return kind == Kind.NULL;
    }

    public boolean isDefault() {
        return kind == Kind.DEFAULT;
    }

    public boolean isExpression() {
        return kind == Kind.EXPRESSION;
    }

    public boolean isTextLike() {
        return kind == Kind.STRING || kind == Kind.EXPRESSION;
    }

    /**
     * A stable, type-aware representation used by tests and by the optional
     * verifier.  Integer 1 and DECIMAL 1.0 compare equal.
     */
    public String canonical() {
        return switch (kind) {
            case NULL -> "N";
            case STRING -> "S" + string.length() + ":" + string;
            case BYTES -> "B" + HexFormat.of().formatHex(bytes);
            case NUMBER -> "D" + canonicalNumber(number);
            case BOOLEAN -> "D" + expression;
            case DEFAULT -> "T";
            case EXPRESSION -> "E" + expression;
        };
    }

    private static String canonicalNumber(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) {
            normalized = normalized.setScale(0);
        }
        return normalized.toPlainString();
    }

    public String describe() {
        return switch (kind) {
            case NULL -> "NULL";
            case STRING -> "'" + (string.length() > 60 ? string.substring(0, 60) + "..." : string) + "'";
            case BYTES -> "X'" + HexFormat.of().formatHex(bytes) + "'";
            case NUMBER -> number.toPlainString();
            case BOOLEAN -> expression;
            case DEFAULT -> "DEFAULT";
            case EXPRESSION -> expression;
        };
    }

    public int approximateUtf8Length() {
        return switch (kind) {
            case NULL, DEFAULT, BOOLEAN -> 8;
            case NUMBER -> number == null ? 8 : number.toPlainString().length() + 4;
            case BYTES -> bytes.length * 2 + 8;
            case STRING -> string.getBytes(StandardCharsets.UTF_8).length + 8;
            case EXPRESSION -> expression.getBytes(StandardCharsets.UTF_8).length + 8;
        };
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Value other)) {
            return false;
        }
        return canonical().equals(other.canonical());
    }

    @Override
    public int hashCode() {
        return Objects.hash(canonical());
    }

    @Override
    public String toString() {
        return describe();
    }

    public byte[] bytesCopy() {
        return bytes == null ? null : Arrays.copyOf(bytes, bytes.length);
    }
}
