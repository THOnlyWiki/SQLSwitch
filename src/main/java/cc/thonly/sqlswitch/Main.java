package cc.thonly.sqlswitch;

/**
 * Entry point.  Kept tiny so the CLI can also be embedded in tests.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        // Silence the SLF4J simple logger used by the JDBC drivers unless the
        // user explicitly asks for verbose output.
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        }
        int code = new Cli().run(args);
        if (code != 0) {
            System.exit(code);
        }
    }
}
