package cc.thonly.sqlswitch.parse;

import java.util.List;

public final class TokenStream {
    private final List<Token> tokens;
    private final String sql;
    private int index;

    public TokenStream(String sql, List<Token> tokens) {
        this.sql = sql;
        this.tokens = tokens;
    }

    public String sql() {
        return sql;
    }

    public int index() {
        return index;
    }

    public void index(int newIndex) {
        this.index = Math.max(0, Math.min(newIndex, tokens.size() - 1));
    }

    public Token peek() {
        return tokens.get(Math.min(index, tokens.size() - 1));
    }

    public Token peek(int offset) {
        return tokens.get(Math.min(index + offset, tokens.size() - 1));
    }

    public Token next() {
        Token token = peek();
        if (index < tokens.size() - 1) {
            index++;
        }
        return token;
    }

    public boolean atEnd() {
        return peek().isEof();
    }

    public boolean atSymbol(String symbol) {
        return peek().isSymbol(symbol);
    }

    public boolean matchSymbol(String symbol) {
        if (atSymbol(symbol)) {
            next();
            return true;
        }
        return false;
    }

    public void expectSymbol(String symbol) {
        if (!matchSymbol(symbol)) {
            Token t = peek();
            throw new ParseException("Expected '" + symbol + "' but found '" + t.text + "'", t.line, t.column);
        }
    }

    public boolean matchKeyword(String keyword) {
        if (peek().isKeyword(keyword)) {
            next();
            return true;
        }
        return false;
    }

    public void expectKeyword(String keyword) {
        if (!matchKeyword(keyword)) {
            Token t = peek();
            throw new ParseException("Expected " + keyword + " but found '" + t.text + "'", t.line, t.column);
        }
    }

    public String expectIdentifier() {
        Token t = peek();
        if (!t.isIdentifier()) {
            throw new ParseException("Expected identifier but found '" + t.text + "'", t.line, t.column);
        }
        next();
        return t.text;
    }

    public String readQualifiedIdentifier() {
        String first = expectIdentifier();
        StringBuilder name = new StringBuilder(first);
        while (matchSymbol(".")) {
            name.append('.').append(expectIdentifier());
        }
        return name.toString();
    }

    public void skipBalanced(char open, char close) {
        expectSymbol(String.valueOf(open));
        int depth = 1;
        while (!atEnd()) {
            Token t = next();
            if (t.kind == Token.Kind.SYMBOL) {
                if (t.text.equals(String.valueOf(open))) {
                    depth++;
                } else if (t.text.equals(String.valueOf(close))) {
                    depth--;
                    if (depth == 0) {
                        return;
                    }
                }
            }
        }
        throw new ParseException("Unbalanced " + open + close + " group", peek().line, peek().column);
    }

    /**
     * Returns the original SQL text covered by tokens [startIndex, endIndex).
     * Used for CHECK/generated expressions and unknown constructs.
     */
    public String rawBetween(int startIndex, int endIndex) {
        if (startIndex < 0 || startIndex >= tokens.size() || endIndex <= startIndex) {
            return "";
        }
        int from = tokens.get(startIndex).start;
        int to = tokens.get(Math.min(endIndex - 1, tokens.size() - 1)).end;
        return sql.substring(from, Math.min(to, sql.length()));
    }

    public List<Token> tokens() {
        return tokens;
    }

    public void skipStatementTail() {
        while (!atEnd() && !atSymbol(";")) {
            next();
        }
        matchSymbol(";");
    }
}
