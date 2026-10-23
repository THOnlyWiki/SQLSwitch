package cc.thonly.sqlswitch.parse;

import cc.thonly.sqlswitch.model.Dialect;

import java.util.ArrayList;
import java.util.List;

/**
 * A small, dump-oriented SQL tokenizer.  It is not a full SQL grammar parser,
 * but it understands all quoting and escaping rules that mysqldump, Navicat,
 * phpMyAdmin and sqlite3 produce.
 */
public final class Tokenizer {
    private final String sql;
    private final Dialect dialect;
    private final boolean backslashEscapes;
    private int pos;
    private int line = 1;
    private int column = 1;

    public Tokenizer(String sql, Dialect dialect, boolean backslashEscapes) {
        this.sql = sql;
        this.dialect = dialect;
        this.backslashEscapes = backslashEscapes;
    }

    public Tokenizer(String sql, Dialect dialect) {
        this(sql, dialect, dialect != null && dialect.isMySqlFamily());
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= sql.length()) {
                tokens.add(new Token(Token.Kind.EOF, "", "", pos, pos, line, column, '\0'));
                return tokens;
            }
            char c = sql.charAt(pos);
            int start = pos;
            int startLine = line;
            int startColumn = column;

            if (c == '\'' || (c == '"' && (dialect == Dialect.MYSQL || dialect == Dialect.MARIADB))) {
                tokens.add(readString(c, start, startLine, startColumn, false));
                continue;
            }
            if (c == '"' || c == '`') {
                tokens.add(readQuotedIdentifier(c, start, startLine, startColumn));
                continue;
            }
            if (c == '[') {
                tokens.add(readBracketIdentifier(start, startLine, startColumn));
                continue;
            }
            if ((c == 'X' || c == 'x') && pos + 1 < sql.length() && sql.charAt(pos + 1) == '\'') {
                tokens.add(readBytesLiteral(start, startLine, startColumn));
                continue;
            }
            if ((c == 'B' || c == 'b') && pos + 1 < sql.length() && sql.charAt(pos + 1) == '\'') {
                tokens.add(readBitLiteral(start, startLine, startColumn));
                continue;
            }
            if ((c == 'N' || c == 'n') && pos + 1 < sql.length() && sql.charAt(pos + 1) == '\'') {
                advance();
                Token string = readString('\'', start, startLine, startColumn, true);
                tokens.add(new Token(Token.Kind.STRING, string.text, string.raw, string.start, string.end,
                        string.line, string.column, '\''));
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && pos + 1 < sql.length()
                    && Character.isDigit(sql.charAt(pos + 1)))) {
                tokens.add(readNumber(start, startLine, startColumn));
                continue;
            }
            if (isIdentifierStart(c)) {
                tokens.add(readIdentifier(start, startLine, startColumn));
                continue;
            }
            String symbol = readSymbol();
            tokens.add(new Token(Token.Kind.SYMBOL, symbol, symbol, start, pos, startLine, startColumn, '\0'));
        }
    }

    private Token readString(char quote, int start, int startLine, int startColumn, boolean national) {
        advance(); // opening quote
        StringBuilder value = new StringBuilder();
        while (pos < sql.length()) {
            char c = sql.charAt(pos);
            if (c == quote) {
                if (pos + 1 < sql.length() && sql.charAt(pos + 1) == quote) {
                    value.append(quote);
                    advance();
                    advance();
                    continue;
                }
                advance();
                return new Token(Token.Kind.STRING, value.toString(), sql.substring(start, pos),
                        start, pos, startLine, startColumn, quote);
            }
            if (backslashEscapes && c == '\\') {
                advance();
                if (pos >= sql.length()) {
                    break;
                }
                char e = sql.charAt(pos);
                advance();
                switch (e) {
                    case '0' -> value.append('\0');
                    case 'b' -> value.append('\b');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'Z' -> value.append((char) 0x1A);
                    case '\\' -> value.append('\\');
                    case '\'' -> value.append('\'');
                    case '"' -> value.append('"');
                    case '%' -> value.append('\\').append('%');
                    case '_' -> value.append('\\').append('_');
                    default -> value.append(e);
                }
                continue;
            }
            value.append(c);
            advance();
        }
        throw new ParseException("Unterminated string literal", startLine, startColumn);
    }

    private Token readQuotedIdentifier(char quote, int start, int startLine, int startColumn) {
        advance();
        StringBuilder value = new StringBuilder();
        while (pos < sql.length()) {
            char c = sql.charAt(pos);
            if (c == quote) {
                if (pos + 1 < sql.length() && sql.charAt(pos + 1) == quote) {
                    value.append(quote);
                    advance();
                    advance();
                    continue;
                }
                advance();
                return new Token(Token.Kind.QUOTED_IDENT, value.toString(), sql.substring(start, pos),
                        start, pos, startLine, startColumn, quote);
            }
            value.append(c);
            advance();
        }
        throw new ParseException("Unterminated quoted identifier", startLine, startColumn);
    }

    private Token readBracketIdentifier(int start, int startLine, int startColumn) {
        advance();
        StringBuilder value = new StringBuilder();
        while (pos < sql.length()) {
            char c = sql.charAt(pos);
            if (c == ']') {
                if (pos + 1 < sql.length() && sql.charAt(pos + 1) == ']') {
                    value.append(']');
                    advance();
                    advance();
                    continue;
                }
                advance();
                return new Token(Token.Kind.QUOTED_IDENT, value.toString(), sql.substring(start, pos),
                        start, pos, startLine, startColumn, ']');
            }
            value.append(c);
            advance();
        }
        throw new ParseException("Unterminated bracket identifier", startLine, startColumn);
    }

    private Token readBytesLiteral(int start, int startLine, int startColumn) {
        advance(); // X
        advance(); // '
        StringBuilder hex = new StringBuilder();
        while (pos < sql.length()) {
            char c = sql.charAt(pos);
            advance();
            if (c == '\'') {
                return new Token(Token.Kind.BYTES, hex.toString(), sql.substring(start, pos),
                        start, pos, startLine, startColumn, '\'');
            }
            if (!Character.isWhitespace(c)) {
                hex.append(c);
            }
        }
        throw new ParseException("Unterminated X'...' literal", startLine, startColumn);
    }

    private Token readBitLiteral(int start, int startLine, int startColumn) {
        advance(); // B
        advance(); // '
        StringBuilder bits = new StringBuilder();
        while (pos < sql.length()) {
            char c = sql.charAt(pos);
            advance();
            if (c == '\'') {
                return new Token(Token.Kind.BIT, bits.toString(), sql.substring(start, pos),
                        start, pos, startLine, startColumn, '\'');
            }
            if (!Character.isWhitespace(c)) {
                bits.append(c);
            }
        }
        throw new ParseException("Unterminated B'...' literal", startLine, startColumn);
    }

    private Token readNumber(int start, int startLine, int startColumn) {
        boolean hex = false;
        if (pos + 1 < sql.length() && sql.charAt(pos) == '0'
                && (sql.charAt(pos + 1) == 'x' || sql.charAt(pos + 1) == 'X')) {
            hex = true;
            advance();
            advance();
            while (pos < sql.length() && isHexDigit(sql.charAt(pos))) {
                advance();
            }
        } else {
            while (pos < sql.length() && Character.isDigit(sql.charAt(pos))) {
                advance();
            }
            if (pos < sql.length() && sql.charAt(pos) == '.') {
                advance();
                while (pos < sql.length() && Character.isDigit(sql.charAt(pos))) {
                    advance();
                }
            }
            if (pos < sql.length() && (sql.charAt(pos) == 'e' || sql.charAt(pos) == 'E')) {
                int save = pos;
                advance();
                if (pos < sql.length() && (sql.charAt(pos) == '+' || sql.charAt(pos) == '-')) {
                    advance();
                }
                if (pos < sql.length() && Character.isDigit(sql.charAt(pos))) {
                    while (pos < sql.length() && Character.isDigit(sql.charAt(pos))) {
                        advance();
                    }
                } else {
                    pos = save;
                }
            }
        }
        String text = sql.substring(start, pos);
        if (hex && dialect != Dialect.SQLITE) {
            return new Token(Token.Kind.BYTES, text.substring(2), text, start, pos, startLine, startColumn, '\0');
        }
        return new Token(Token.Kind.NUMBER, text, text, start, pos, startLine, startColumn, '\0');
    }

    private Token readIdentifier(int start, int startLine, int startColumn) {
        while (pos < sql.length() && isIdentifierPart(sql.charAt(pos))) {
            advance();
        }
        String text = sql.substring(start, pos);
        return new Token(Token.Kind.IDENT, text, text, start, pos, startLine, startColumn, '\0');
    }

    private String readSymbol() {
        char c = sql.charAt(pos);
        if (pos + 1 < sql.length()) {
            String two = sql.substring(pos, pos + 2);
            if (two.equals("<=") || two.equals(">=") || two.equals("<>") || two.equals("!=")
                    || two.equals("||") || two.equals("&&") || two.equals(":=")
                    || two.equals("->") || two.equals("<<") || two.equals(">>")) {
                advance();
                advance();
                return two;
            }
        }
        advance();
        return String.valueOf(c);
    }

    private void skipWhitespaceAndComments() {
        while (pos < sql.length()) {
            char c = sql.charAt(pos);
            if (Character.isWhitespace(c)) {
                advance();
                continue;
            }
            if (c == '-' && pos + 1 < sql.length() && sql.charAt(pos + 1) == '-') {
                if (dialect == Dialect.SQLITE || pos + 2 >= sql.length()
                        || Character.isWhitespace(sql.charAt(pos + 2))) {
                    skipLineComment();
                    continue;
                }
            }
            if (c == '#' && dialect.isMySqlFamily()) {
                skipLineComment();
                continue;
            }
            if (c == '/' && pos + 1 < sql.length() && sql.charAt(pos + 1) == '*') {
                skipBlockComment();
                continue;
            }
            return;
        }
    }

    private void skipLineComment() {
        while (pos < sql.length()) {
            char c = sql.charAt(pos);
            advance();
            if (c == '\n') {
                return;
            }
        }
    }

    private void skipBlockComment() {
        advance();
        advance();
        while (pos + 1 < sql.length()) {
            char c = sql.charAt(pos);
            if (c == '*' && sql.charAt(pos + 1) == '/') {
                advance();
                advance();
                return;
            }
            advance();
        }
        pos = sql.length();
    }

    private void advance() {
        char c = sql.charAt(pos);
        pos++;
        if (c == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$' || c == '@' || c >= 0x80;
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '@' || c == '#' || c >= 0x80;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
