package cc.thonly.sqlswitch.parse;

import java.util.Locale;

public final class Token {
    public enum Kind {
        IDENT,
        QUOTED_IDENT,
        STRING,
        NUMBER,
        BYTES,
        BIT,
        SYMBOL,
        EOF
    }

    public final Kind kind;
    public final String text;
    public final String raw;
    public final int start;
    public final int end;
    public final int line;
    public final int column;
    public final char quote;

    public Token(Kind kind, String text, String raw, int start, int end, int line, int column, char quote) {
        this.kind = kind;
        this.text = text;
        this.raw = raw;
        this.start = start;
        this.end = end;
        this.line = line;
        this.column = column;
        this.quote = quote;
    }

    public boolean isKeyword(String keyword) {
        return (kind == Kind.IDENT) && text.equalsIgnoreCase(keyword);
    }

    public boolean isSymbol(String symbol) {
        return kind == Kind.SYMBOL && text.equals(symbol);
    }

    public boolean isIdentifier() {
        return kind == Kind.IDENT || kind == Kind.QUOTED_IDENT;
    }

    public String upper() {
        return text == null ? "" : text.toUpperCase(Locale.ROOT);
    }

    public boolean isEof() {
        return kind == Kind.EOF;
    }

    @Override
    public String toString() {
        return kind + "(" + (text == null ? "" : text) + ")@" + line + ":" + column;
    }
}
