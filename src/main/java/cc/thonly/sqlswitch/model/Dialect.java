package cc.thonly.sqlswitch.model;

import java.util.Locale;

/**
 * The SQL flavour of a dump or of a target database.
 */
public enum Dialect {
    SQLITE,
    MYSQL,
    MARIADB;

    public boolean isMySqlFamily() {
        return this == MYSQL || this == MARIADB;
    }

    public boolean isMariaDb() {
        return this == MARIADB;
    }

    public boolean isSqlite() {
        return this == SQLITE;
    }

    public String displayName() {
        return switch (this) {
            case SQLITE -> "SQLite";
            case MYSQL -> "MySQL";
            case MARIADB -> "MariaDB";
        };
    }

    public static Dialect parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "sqlite", "sqlite3", "db" -> SQLITE;
            case "mysql", "mysqldump", "mysql8" -> MYSQL;
            case "mariadb", "maria", "mariadb-dump" -> MARIADB;
            default -> throw new IllegalArgumentException("Unknown dialect: " + value
                    + " (expected sqlite, mysql or mariadb)");
        };
    }
}
