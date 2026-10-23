package cc.thonly.sqlswitch.parse;

import cc.thonly.sqlswitch.model.Dialect;
import cc.thonly.sqlswitch.model.Value;
import cc.thonly.sqlswitch.util.TextUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * Parses INSERT values and DEFAULT expressions.
 */
public final class ValueParser {
    private static final Set<String> EXPRESSION_KEYWORDS = Set.of(
            "CURRENT_TIMESTAMP", "CURRENT_DATE", "CURRENT_TIME",
            "LOCALTIME", "LOCALTIMESTAMP", "NOW", "UUID", "RANDOM"
    );

    private final Dialect dialect;

    public ValueParser(Dialect dialect) {
        this.dialect = dialect;
    }

    public Value parseValue(TokenStream ts) {
        Token token = ts.peek();
        if (token.isSymbol("-") || token.isSymbol("+")) {
            boolean negative = token.isSymbol("-");
            int start = ts.index();
            ts.next();
            if (ts.peek().kind == Token.Kind.NUMBER) {
                BigDecimal number = parseNumber(ts.next().text);
                return Value.number(negative ? number.negate() : number);
            }
            ts.index(start);
            return Value.expression(readRawValue(ts));
        }
        if (token.kind == Token.Kind.STRING) {
            ts.next();
            return Value.string(token.text);
        }
        if (token.kind == Token.Kind.NUMBER) {
            ts.next();
            return Value.number(parseNumber(token.text));
        }
        if (token.kind == Token.Kind.BYTES) {
            ts.next();
            return Value.bytes(parseHex(token.text));
        }
        if (token.kind == Token.Kind.BIT) {
            ts.next();
            return parseBit(token.text);
        }
        if (token.kind == Token.Kind.QUOTED_IDENT) {
            ts.next();
            return dialect == Dialect.SQLITE ? Value.string(token.text) : Value.expression(token.raw);
        }
        if (token.kind == Token.Kind.IDENT) {
            String upper = token.upper();
            if (upper.equals("NULL")) {
                ts.next();
                return Value.NULL;
            }
            if (upper.equals("TRUE")) {
                ts.next();
                return Value.bool(true);
            }
            if (upper.equals("FALSE")) {
                ts.next();
                return Value.bool(false);
            }
            if (upper.equals("DEFAULT")) {
                ts.next();
                return Value.DEFAULT;
            }
            if (isCharsetIntroducer(token, ts.peek(1))) {
                ts.next();
                Token string = ts.next();
                if (upper.equals("_BINARY")) {
                    return Value.bytes(string.text.getBytes(StandardCharsets.UTF_8));
                }
                if (upper.equals("_LATIN1")) {
                    return Value.bytes(string.text.getBytes(StandardCharsets.ISO_8859_1));
                }
                return Value.string(string.text);
            }
            if (upper.equals("_BINARY") && ts.peek(1).kind == Token.Kind.BYTES) {
                ts.next();
                Token bytes = ts.next();
                return Value.bytes(parseHex(bytes.text));
            }
            if ((upper.equals("DATE") || upper.equals("TIME") || upper.equals("TIMESTAMP"))
                    && ts.peek(1).kind == Token.Kind.STRING) {
                ts.next();
                return Value.string(ts.next().text);
            }
            if (EXPRESSION_KEYWORDS.contains(upper)) {
                ts.next();
                String raw = token.raw;
                if (ts.atSymbol("(")) {
                    raw += readBalanced(ts);
                }
                return Value.expression(raw);
            }
            if (ts.peek(1).isSymbol("(")) {
                return Value.expression(readRawValue(ts));
            }
            ts.next();
            return Value.expression(token.raw);
        }
        if (token.isSymbol("(")) {
            return Value.expression(readRawValue(ts));
        }
        return Value.expression(readRawValue(ts));
    }

    public Value parseDefault(TokenStream ts) {
        Token token = ts.peek();
        if (token.isSymbol("-") || token.isSymbol("+")) {
            boolean negative = token.isSymbol("-");
            int start = ts.index();
            ts.next();
            if (ts.peek().kind == Token.Kind.NUMBER) {
                BigDecimal number = parseNumber(ts.next().text);
                return Value.number(negative ? number.negate() : number);
            }
            ts.index(start);
            return Value.expression(readDefaultExpression(ts));
        }
        if (token.kind == Token.Kind.STRING) {
            ts.next();
            return Value.string(token.text);
        }
        if (token.kind == Token.Kind.NUMBER) {
            ts.next();
            return Value.number(parseNumber(token.text));
        }
        if (token.kind == Token.Kind.BYTES) {
            ts.next();
            return Value.bytes(parseHex(token.text));
        }
        if (token.kind == Token.Kind.BIT) {
            ts.next();
            return parseBit(token.text);
        }
        if (token.kind == Token.Kind.QUOTED_IDENT) {
            ts.next();
            if (dialect == Dialect.SQLITE) {
                return Value.string(token.text);
            }
            return Value.expression(token.raw);
        }
        if (token.kind == Token.Kind.IDENT) {
            String upper = token.upper();
            if (upper.equals("NULL")) {
                ts.next();
                return Value.NULL;
            }
            if (upper.equals("TRUE")) {
                ts.next();
                return Value.bool(true);
            }
            if (upper.equals("FALSE")) {
                ts.next();
                return Value.bool(false);
            }
            if (isCharsetIntroducer(token, ts.peek(1))) {
                ts.next();
                Token string = ts.next();
                if (upper.equals("_BINARY")) {
                    return Value.bytes(string.text.getBytes(StandardCharsets.UTF_8));
                }
                if (upper.equals("_LATIN1")) {
                    return Value.bytes(string.text.getBytes(StandardCharsets.ISO_8859_1));
                }
                return Value.string(string.text);
            }
            if (EXPRESSION_KEYWORDS.contains(upper) || isFunctionCall(ts)) {
                String raw = readDefaultExpression(ts);
                return Value.expression(raw);
            }
            if ((upper.equals("DATE") || upper.equals("TIME") || upper.equals("TIMESTAMP"))
                    && ts.peek(1).kind == Token.Kind.STRING) {
                ts.next();
                return Value.string(ts.next().text);
            }
            ts.next();
            if (dialect == Dialect.SQLITE) {
                // SQLite stores a bare keyword in DEFAULT as a string literal.
                return Value.string(token.text);
            }
            return Value.expression(token.raw);
        }
        if (token.isSymbol("(")) {
            int start = ts.index();
            String raw = readParenthesizedRaw(ts);
            Value simplified = simplifyParenthesized(raw, ts, start);
            return simplified != null ? simplified : Value.expression(raw);
        }
        return Value.expression(readDefaultExpression(ts));
    }

    private Value simplifyParenthesized(String raw, TokenStream ts, int startIndex) {
        // Re-tokenizing the inner expression is cheap and lets us keep
        // DEFAULT ('abc') as a real string rather than an opaque expression.
        String inner = raw.trim();
        if (inner.startsWith("(") && inner.endsWith(")")) {
            inner = inner.substring(1, inner.length() - 1).trim();
        }
        if (inner.isEmpty()) {
            return null;
        }
        try {
            Tokenizer tokenizer = new Tokenizer(inner, dialect);
            TokenStream innerStream = new TokenStream(inner, tokenizer.tokenize());
            Value v = parseDefault(innerStream);
            if (!innerStream.atEnd()) {
                return null;
            }
            return v.isExpression() ? null : v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static boolean isCharsetIntroducer(Token token, Token next) {
        return token.kind == Token.Kind.IDENT
                && token.text.startsWith("_")
                && token.text.length() > 1
                && next.kind == Token.Kind.STRING;
    }

    private boolean isFunctionCall(TokenStream ts) {
        return ts.peek().kind == Token.Kind.IDENT && ts.peek(1).isSymbol("(");
    }

    private String readDefaultExpression(TokenStream ts) {
        int start = ts.index();
        int depth = 0;
        while (!ts.atEnd()) {
            Token t = ts.peek();
            if (t.kind == Token.Kind.SYMBOL) {
                if (t.text.equals("(")) {
                    depth++;
                } else if (t.text.equals(")")) {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                } else if (depth == 0 && (t.text.equals(",") || t.text.equals(";"))) {
                    break;
                }
            } else if (depth == 0 && t.kind == Token.Kind.IDENT && isColumnConstraintKeyword(t.text)) {
                break;
            }
            ts.next();
        }
        return ts.rawBetween(start, ts.index()).trim();
    }

    private String readRawValue(TokenStream ts) {
        int start = ts.index();
        int depth = 0;
        while (!ts.atEnd()) {
            Token t = ts.peek();
            if (t.kind == Token.Kind.SYMBOL) {
                if (t.text.equals("(")) {
                    depth++;
                } else if (t.text.equals(")")) {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                } else if (depth == 0 && (t.text.equals(",") || t.text.equals(";"))) {
                    break;
                }
            }
            ts.next();
        }
        return ts.rawBetween(start, ts.index()).trim();
    }

    private String readBalanced(TokenStream ts) {
        int start = ts.index();
        ts.expectSymbol("(");
        int depth = 1;
        while (!ts.atEnd() && depth > 0) {
            Token t = ts.next();
            if (t.isSymbol("(")) {
                depth++;
            } else if (t.isSymbol(")")) {
                depth--;
            }
        }
        return ts.rawBetween(start, ts.index());
    }

    private String readParenthesizedRaw(TokenStream ts) {
        int start = ts.index();
        ts.expectSymbol("(");
        int depth = 1;
        while (!ts.atEnd() && depth > 0) {
            Token t = ts.next();
            if (t.isSymbol("(")) {
                depth++;
            } else if (t.isSymbol(")")) {
                depth--;
            }
        }
        return ts.rawBetween(start, ts.index());
    }

    private static boolean isColumnConstraintKeyword(String word) {
        return switch (word.toUpperCase(Locale.ROOT)) {
            case "NOT", "NULL", "PRIMARY", "UNIQUE", "AUTO_INCREMENT", "AUTOINCREMENT",
                    "COMMENT", "COLLATE", "CHARACTER", "CHARSET", "REFERENCES", "CHECK",
                    "GENERATED", "AS", "VISIBLE", "INVISIBLE", "ON" -> true;
            default -> false;
        };
    }

    public static BigDecimal parseNumber(String text) {
        if (text == null || text.isBlank()) {
            return BigDecimal.ZERO;
        }
        String t = text.trim();
        if (t.startsWith("0x") || t.startsWith("0X")) {
            return new BigDecimal(new BigInteger(t.substring(2), 16));
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    public static byte[] parseHex(String hex) {
        String value = hex == null ? "" : hex.trim();
        if (value.startsWith("0x") || value.startsWith("0X")) {
            value = value.substring(2);
        }
        if (value.length() % 2 != 0) {
            value = "0" + value;
        }
        if (value.isEmpty()) {
            return new byte[0];
        }
        return HexFormat.of().parseHex(value);
    }

    private static Value parseBit(String bits) {
        String value = bits == null ? "" : bits.trim();
        if (value.isEmpty()) {
            return Value.number(0);
        }
        if (value.length() <= 63) {
            return Value.number(new BigInteger(value, 2));
        }
        String padded = value;
        while (padded.length() % 8 != 0) {
            padded = "0" + padded;
        }
        byte[] bytes = new byte[padded.length() / 8];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(padded.substring(i * 8, i * 8 + 8), 2);
        }
        return Value.bytes(bytes);
    }

    public static String normalizeExpression(String raw) {
        return TextUtils.firstLine(raw);
    }
}
