package cc.thonly.sqlswitch.parse;

public class ParseException extends RuntimeException {
    private final int line;
    private final int column;

    public ParseException(String message, int line, int column) {
        super(line > 0 ? "line " + line + ", column " + column + ": " + message : message);
        this.line = line;
        this.column = column;
    }

    public ParseException(String message) {
        this(message, -1, -1);
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
