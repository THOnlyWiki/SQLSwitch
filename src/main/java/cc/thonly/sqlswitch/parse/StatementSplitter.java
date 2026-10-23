package cc.thonly.sqlswitch.parse;

import cc.thonly.sqlswitch.model.Dialect;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a SQL dump into statements while respecting strings, quoted
 * identifiers, comments and the MySQL {@code DELIMITER} command.
 */
public final class StatementSplitter {
    private enum State {
        NORMAL,
        QUOTE,
        BACKTICK,
        BRACKET,
        LINE_COMMENT,
        BLOCK_COMMENT
    }

    private final PushbackReader reader;
    private final Dialect dialect;
    private final boolean backslashEscapes;
    private final int maxStatementChars;
    private String delimiter = ";";
    private int line = 1;

    public StatementSplitter(Reader reader, Dialect dialect, boolean backslashEscapes, int maxStatementChars) {
        this.reader = new PushbackReader(reader, 8192);
        this.dialect = dialect;
        this.backslashEscapes = backslashEscapes;
        this.maxStatementChars = maxStatementChars;
    }

    public List<SqlStatement> splitAll() throws IOException {
        List<SqlStatement> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int statementLine = 0;
        boolean lineStart = true;
        State state = State.NORMAL;
        char quote = 0;

        while (true) {
            if (state == State.NORMAL && lineStart && current.toString().isBlank()) {
                if (tryDelimiterCommand()) {
                    lineStart = true;
                    continue;
                }
            }

            if (state == State.NORMAL && matchesDelimiter()) {
                emit(result, current, statementLine);
                current.setLength(0);
                statementLine = 0;
                lineStart = false;
                continue;
            }

            int ci = reader.read();
            if (ci < 0) {
                break;
            }
            char c = (char) ci;

            switch (state) {
                case NORMAL -> {
                    if (c == '\'') {
                        if (statementLine == 0) {
                            statementLine = line;
                        }
                        append(current, c);
                        state = State.QUOTE;
                        quote = c;
                        lineStart = false;
                    } else if (c == '"' && dialect.isMySqlFamily()) {
                        if (statementLine == 0) {
                            statementLine = line;
                        }
                        append(current, c);
                        state = State.QUOTE;
                        quote = c;
                        lineStart = false;
                    } else if (c == '"') {
                        if (statementLine == 0) {
                            statementLine = line;
                        }
                        append(current, c);
                        state = State.QUOTE;
                        quote = c;
                        lineStart = false;
                    } else if (c == '`') {
                        if (statementLine == 0) {
                            statementLine = line;
                        }
                        append(current, c);
                        state = State.BACKTICK;
                        lineStart = false;
                    } else if (c == '[') {
                        if (statementLine == 0) {
                            statementLine = line;
                        }
                        append(current, c);
                        state = State.BRACKET;
                        lineStart = false;
                    } else if (c == '-' && peekIs('-')) {
                        int third = peekCharAt(2);
                        if (dialect == Dialect.SQLITE || third < 0 || Character.isWhitespace((char) third)) {
                            read(); // consume the second '-'
                            skipLineComment();
                            current.append(' ');
                            lineStart = true;
                        } else {
                            if (statementLine == 0) {
                                statementLine = line;
                            }
                            append(current, c);
                            lineStart = false;
                        }
                    } else if (c == '#' && dialect.isMySqlFamily()) {
                        skipLineComment();
                        current.append(' ');
                        lineStart = true;
                    } else if (c == '/' && peekIs('*')) {
                        read(); // consume '*'
                        skipBlockComment();
                        current.append(' ');
                        lineStart = false;
                    } else {
                        if (statementLine == 0 && !Character.isWhitespace(c)) {
                            statementLine = line;
                        }
                        append(current, c);
                        if (c == '\n') {
                            lineStart = true;
                        } else if (!Character.isWhitespace(c)) {
                            lineStart = false;
                        }
                    }
                }
                case QUOTE -> {
                    append(current, c);
                    if (c == quote) {
                        int next = read();
                        if (next == quote) {
                            append(current, (char) next);
                        } else {
                            if (next >= 0) {
                                unread((char) next);
                            }
                            state = State.NORMAL;
                        }
                    } else if (backslashEscapes && c == '\\') {
                        int next = read();
                        if (next >= 0) {
                            append(current, (char) next);
                        }
                    }
                }
                case BACKTICK -> {
                    append(current, c);
                    if (c == '`') {
                        int next = read();
                        if (next == '`') {
                            append(current, (char) next);
                        } else {
                            if (next >= 0) {
                                unread((char) next);
                            }
                            state = State.NORMAL;
                        }
                    }
                }
                case BRACKET -> {
                    append(current, c);
                    if (c == ']') {
                        int next = read();
                        if (next == ']') {
                            append(current, (char) next);
                        } else {
                            if (next >= 0) {
                                unread((char) next);
                            }
                            state = State.NORMAL;
                        }
                    }
                }
                default -> {
                    // Comments are handled inline in NORMAL; this branch is only defensive.
                    state = State.NORMAL;
                }
            }

            if (current.length() > maxStatementChars) {
                throw new ParseException("Statement is larger than the configured limit of "
                        + maxStatementChars + " characters", line, 1);
            }
        }

        if (state == State.QUOTE || state == State.BACKTICK || state == State.BRACKET) {
            throw new ParseException("Unterminated quoted literal at end of file", line, 1);
        }
        emit(result, current, statementLine);
        return result;
    }

    private void emit(List<SqlStatement> result, StringBuilder current, int statementLine) {
        String text = current.toString().trim();
        if (!text.isEmpty()) {
            result.add(new SqlStatement(text, statementLine > 0 ? statementLine : line));
        }
    }

    private boolean tryDelimiterCommand() throws IOException {
        StringBuilder probe = new StringBuilder();
        int max = 64;
        while (probe.length() < max) {
            int ci = reader.read();
            if (ci < 0) {
                break;
            }
            char c = (char) ci;
            probe.append(c);
            if (c == '\n') {
                break;
            }
        }
        String trimmed = probe.toString().trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.startsWith("DELIMITER") && (trimmed.length() == 9
                || Character.isWhitespace(trimmed.charAt(9)))) {
            String value = trimmed.substring(9).trim();
            if (value.isEmpty()) {
                throw new ParseException("DELIMITER command without a delimiter", line, 1);
            }
            delimiter = value;
            for (int i = 0; i < probe.length(); i++) {
                if (probe.charAt(i) == '\n') {
                    line++;
                }
            }
            return true;
        }
        for (int i = probe.length() - 1; i >= 0; i--) {
            reader.unread(probe.charAt(i));
        }
        return false;
    }

    private boolean matchesDelimiter() throws IOException {
        char[] buf = new char[delimiter.length()];
        int read = 0;
        while (read < buf.length) {
            int ci = reader.read();
            if (ci < 0) {
                break;
            }
            buf[read++] = (char) ci;
        }
        boolean match = read == buf.length && new String(buf).equals(delimiter);
        if (!match) {
            for (int i = read - 1; i >= 0; i--) {
                reader.unread(buf[i]);
            }
            return false;
        }
        for (char c : buf) {
            if (c == '\n') {
                line++;
            }
        }
        return true;
    }

    private void skipLineComment() throws IOException {
        while (true) {
            int ci = reader.read();
            if (ci < 0) {
                return;
            }
            if (ci == '\n') {
                line++;
                return;
            }
        }
    }

    private void skipBlockComment() throws IOException {
        int a = reader.read();
        while (a >= 0) {
            int b = reader.read();
            if (a == '*' && b == '/') {
                return;
            }
            if (a == '\n') {
                line++;
            }
            a = b;
        }
    }

    private boolean peekIs(char expected) throws IOException {
        int ci = reader.read();
        if (ci < 0) {
            return false;
        }
        reader.unread(ci);
        return ci == expected;
    }

    private int peekCharAt(int offset) throws IOException {
        char[] buf = new char[offset];
        int n = 0;
        while (n < offset) {
            int ci = reader.read();
            if (ci < 0) {
                break;
            }
            buf[n++] = (char) ci;
        }
        for (int i = n - 1; i >= 0; i--) {
            reader.unread(buf[i]);
        }
        return n == offset ? buf[offset - 1] : -1;
    }

    private int read() throws IOException {
        return reader.read();
    }

    private void unread(char c) throws IOException {
        reader.unread(c);
    }

    private void append(StringBuilder current, char c) {
        current.append(c);
        if (c == '\n') {
            line++;
        }
    }

    public record SqlStatement(String text, int line) {
    }
}
