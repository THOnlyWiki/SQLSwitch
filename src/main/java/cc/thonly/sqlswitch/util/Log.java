package cc.thonly.sqlswitch.util;

import java.io.PrintStream;

public final class Log {
    public enum Level {
        QUIET,
        NORMAL,
        VERBOSE
    }

    private static Level level = Level.NORMAL;
    private static final PrintStream OUT = System.out;
    private static final PrintStream ERR = System.err;
    private static PrintStream infoStream = OUT;
    private static int warningCount;

    private Log() {
    }

    public static void setLevel(Level newLevel) {
        level = newLevel == null ? Level.NORMAL : newLevel;
    }

    public static Level getLevel() {
        return level;
    }

    public static boolean verbose() {
        return level == Level.VERBOSE;
    }

    public static void info(String message) {
        if (level != Level.QUIET) {
            infoStream.println(message);
        }
    }

    public static void detail(String message) {
        if (level == Level.VERBOSE) {
            infoStream.println(message);
        }
    }

    public static void infoToStderr(boolean enabled) {
        infoStream = enabled ? ERR : OUT;
    }

    public static void warn(String message) {
        warningCount++;
        if (level != Level.QUIET) {
            ERR.println("[warn] " + message);
        }
    }

    public static void error(String message) {
        ERR.println("[error] " + message);
    }

    public static int warningCount() {
        return warningCount;
    }

    public static void resetWarnings() {
        warningCount = 0;
    }
}
