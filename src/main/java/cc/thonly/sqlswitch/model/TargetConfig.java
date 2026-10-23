package cc.thonly.sqlswitch.model;

import java.util.Locale;

/**
 * Writer configuration.  The defaults are deliberately conservative so that
 * generated dumps import into the MariaDB/MySQL versions found on ordinary
 * developer machines.
 */
public final class TargetConfig {
    public Dialect dialect = Dialect.SQLITE;
    public String serverVersion;
    public String charset;
    public String collation;

    public boolean emitDrop = true;
    public boolean emitCreateDatabase = false;
    public String databaseName;
    public boolean transaction = true;
    public boolean comments = true;
    public boolean includeSchema = true;
    public boolean includeData = true;
    public boolean includeSequence = true;

    public boolean autoWidenVarchar = true;
    public boolean inferTypes = false;
    public boolean inferDates = false;
    public boolean inferBooleans = true;

    public boolean mapTextToLongText = true;
    public boolean useJsonType = false;
    public boolean emitCheckConstraints = true;
    public boolean emitColumnComments = true;
    public boolean sqliteAutoincrement = true;
    public boolean sqlitePreserveDeclaredTypes = false;
    public boolean header = true;
    public boolean bom = false;
    public boolean noTimestamp = false;
    public boolean sortRows = true;

    public int maxRowsPerInsert = 200;
    public int maxStatementBytes = 900_000;

    public TargetConfig() {
    }

    public TargetConfig(Dialect dialect) {
        this.dialect = dialect;
        if (dialect == Dialect.MARIADB) {
            // The machine this project was written for runs MariaDB 10.1.
            // Modern MariaDB users can pass --target-version=10.5 or newer.
            this.serverVersion = "10.1.21";
        } else if (dialect == Dialect.MYSQL) {
            this.serverVersion = "8.0.29";
        }
    }

    public boolean isSqlite() {
        return dialect == Dialect.SQLITE;
    }

    public boolean isMariaDb() {
        return dialect == Dialect.MARIADB;
    }

    public boolean isMySql() {
        return dialect == Dialect.MYSQL;
    }

    public boolean isMySqlFamily() {
        return dialect.isMySqlFamily();
    }

    public boolean isLegacyMariaDb() {
        if (dialect != Dialect.MARIADB) {
            return false;
        }
        return versionAtLeast("10.2.1") == false;
    }

    public boolean isMariaDbCheckSupported() {
        if (dialect != Dialect.MARIADB) {
            return true;
        }
        return versionAtLeast("10.2.1");
    }

    /**
     * MariaDB 10.1 with the default Antelope InnoDB format limits an index key
     * to 767 bytes.  We use this when choosing column charsets.
     */
    public int maxIndexBytes() {
        if (isLegacyMariaDb()) {
            return 767;
        }
        return 3072;
    }

    public String effectiveCharset() {
        if (charset != null && !charset.isBlank()) {
            if (isLegacyMariaDb() && charset.equalsIgnoreCase("utf8mb3")) {
                return "utf8";
            }
            return charset;
        }
        if (collation != null && !collation.isBlank()) {
            String c = collation.toLowerCase(Locale.ROOT);
            if (c.startsWith("utf8mb4")) {
                return "utf8mb4";
            }
            if (c.startsWith("utf8")) {
                return "utf8";
            }
            if (c.startsWith("latin1")) {
                return "latin1";
            }
            if (c.startsWith("ascii")) {
                return "ascii";
            }
        }
        if (isLegacyMariaDb()) {
            return "utf8";
        }
        if (isMySqlFamily()) {
            return "utf8mb4";
        }
        return null;
    }

    public String effectiveCollation() {
        if (collation != null && !collation.isBlank()) {
            return collation;
        }
        if (charset != null && !charset.isBlank()) {
            String c = charset.toLowerCase(Locale.ROOT);
            if (c.contains("utf8mb4")) {
                return "utf8mb4_unicode_ci";
            }
            if (c.contains("utf8") || c.contains("utf8mb3")) {
                return "utf8_general_ci";
            }
            if (c.contains("latin1")) {
                return "latin1_swedish_ci";
            }
            if (c.contains("ascii")) {
                return "ascii_general_ci";
            }
            return charset + "_general_ci";
        }
        if (isLegacyMariaDb()) {
            return "utf8_general_ci";
        }
        if (isMySqlFamily()) {
            return "utf8mb4_unicode_ci";
        }
        return null;
    }

    public String utf8mb4Collation() {
        if (collation != null && collation.toLowerCase(Locale.ROOT).startsWith("utf8mb4")) {
            return collation;
        }
        return isMariaDb() ? "utf8mb4_unicode_ci" : "utf8mb4_unicode_ci";
    }

    public String utf8Collation() {
        return isMariaDb() ? "utf8_general_ci" : "utf8_general_ci";
    }

    public int bytesPerChar(String cs) {
        if (cs == null) {
            return 2;
        }
        String c = cs.toLowerCase(Locale.ROOT);
        if (c.contains("utf8mb4")) {
            return 4;
        }
        if (c.contains("utf8") || c.contains("utf8mb3")) {
            return 3;
        }
        if (c.contains("ucs2") || c.contains("utf16")) {
            return 2;
        }
        if (c.contains("utf32")) {
            return 4;
        }
        if (c.contains("gb18030") || c.contains("gbk") || c.contains("big5")) {
            return 2;
        }
        return 1;
    }

    public int maxCharsForIndex(String cs) {
        return Math.max(1, maxIndexBytes() / bytesPerChar(cs));
    }

    public boolean versionAtLeast(String minimum) {
        if (serverVersion == null || serverVersion.isBlank()) {
            return false;
        }
        return compareVersions(serverVersion, minimum) >= 0;
    }

    public static int compareVersions(String left, String right) {
        String[] a = left.replaceAll("[^0-9.].*$", "").split("\\.");
        String[] b = right.replaceAll("[^0-9.].*$", "").split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length && !a[i].isEmpty() ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length && !b[i].isEmpty() ? Integer.parseInt(b[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }
}
