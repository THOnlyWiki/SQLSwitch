package cc.thonly.sqlswitch.jdbc;

import cc.thonly.sqlswitch.model.Dialect;
import cc.thonly.sqlswitch.parse.StatementSplitter;
import cc.thonly.sqlswitch.util.IoUtils;
import cc.thonly.sqlswitch.util.Log;
import cc.thonly.sqlswitch.util.TextUtils;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Optional end-to-end importer used by the apply command and by the project's
 * verification scripts.  It never touches a database unless the caller names
 * it explicitly, and it refuses system schemas.
 */
public final class JdbcApplier {
    private static final Set<String> SYSTEM_DATABASES = Set.of(
            "mysql", "information_schema", "performance_schema", "sys"
    );

    public static final class Target {
        public Dialect dialect = Dialect.MARIADB;
        public String host = "127.0.0.1";
        public int port;
        public String user = "root";
        public String password = "";
        public String database;
        public boolean createDatabase;
        public boolean dropDatabase;
        public boolean allowExisting;
        public String charset = "utf8mb4";
        public String collation;
    }

    public record ApplyResult(String database, int statements, int tables, long millis) {
        public String summary() {
            return "Applied " + statements + " statements to database `" + database + "` ("
                    + tables + " tables) in " + millis + " ms";
        }
    }

    public ApplyResult apply(Path sqlFile, Target target) throws IOException, SQLException {
        if (target.database == null || target.database.isBlank()) {
            throw new IllegalArgumentException("--database is required");
        }
        String database = target.database.trim();
        if (SYSTEM_DATABASES.contains(database.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Refusing to modify system database: " + database);
        }
        if (target.dialect == null || !target.dialect.isMySqlFamily()) {
            throw new IllegalArgumentException("apply supports mysql or mariadb targets");
        }
        if (target.dropDatabase && !target.createDatabase) {
            throw new IllegalArgumentException("--drop-database requires --create-database");
        }
        int port = target.port <= 0 ? (target.dialect == Dialect.MYSQL ? 3306 : 3306) : target.port;
        loadDriver(target.dialect);
        String baseUrl = baseUrl(target.dialect, target.host, port, null);
        long start = System.currentTimeMillis();
        int statements = 0;
        try (Connection server = DriverManager.getConnection(baseUrl, target.user, target.password)) {
            boolean exists = databaseExists(server, database);
            if (exists) {
                if (target.dropDatabase) {
                    try (Statement statement = server.createStatement()) {
                        statement.execute("DROP DATABASE " + quote(database));
                    }
                    exists = false;
                    Log.info("Dropped database `" + database + "` (explicit --drop-database)");
                } else if (!target.allowExisting && !target.createDatabase) {
                    throw new SQLException("Database `" + database + "` already exists; "
                            + "use --allow-existing to import into it, or --drop-database to recreate it");
                } else if (!target.allowExisting && target.createDatabase) {
                    // CREATE DATABASE IF NOT EXISTS is harmless, but silently
                    // importing into an existing schema is not.  Require an
                    // explicit choice.
                    throw new SQLException("Database `" + database + "` already exists; "
                            + "use --allow-existing or --drop-database");
                }
            }
            if (!exists) {
                if (!target.createDatabase) {
                    throw new SQLException("Database `" + database + "` does not exist; "
                            + "use --create-database");
                }
                String charset = target.charset == null || target.charset.isBlank()
                        ? "utf8mb4" : target.charset;
                String sql = "CREATE DATABASE " + quote(database) + " DEFAULT CHARACTER SET " + charset;
                if (target.collation != null && !target.collation.isBlank()) {
                    sql += " COLLATE " + target.collation;
                }
                try (Statement statement = server.createStatement()) {
                    statement.execute(sql);
                }
                Log.info("Created database `" + database + "`");
            }
        }

        String dbUrl = baseUrl(target.dialect, target.host, port, database);
        try (Connection connection = DriverManager.getConnection(dbUrl, target.user, target.password)) {
            connection.setAutoCommit(true);
            try (Reader reader = IoUtils.openReader(sqlFile)) {
                StatementSplitter splitter = new StatementSplitter(reader, target.dialect, true,
                        256 * 1024 * 1024);
                List<StatementSplitter.SqlStatement> list = splitter.splitAll();
                for (StatementSplitter.SqlStatement sqlStatement : list) {
                    String sql = sqlStatement.text();
                    if (shouldSkip(sql)) {
                        continue;
                    }
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    } catch (SQLException e) {
                        throw new SQLException("line " + sqlStatement.line() + ": " + e.getMessage()
                                + "\n  statement: " + TextUtils.truncate(sql.replace('\n', ' '), 300), e);
                    }
                    statements++;
                    if (statements % 200 == 0) {
                        Log.detail("Executed " + statements + " statements...");
                    }
                }
            }
            int tables = countTables(connection);
            long millis = System.currentTimeMillis() - start;
            return new ApplyResult(database, statements, tables, millis);
        }
    }

    private static boolean shouldSkip(String sql) {
        String s = sql.stripLeading().toUpperCase(Locale.ROOT);
        return s.startsWith("USE ") || s.startsWith("CREATE DATABASE")
                || s.startsWith("DROP DATABASE") || s.startsWith("DELIMITER ");
    }

    private static void loadDriver(Dialect dialect) {
        try {
            if (dialect == Dialect.MYSQL) {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } else {
                Class.forName("org.mariadb.jdbc.Driver");
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("JDBC driver is not on the classpath", e);
        }
    }

    private static String baseUrl(Dialect dialect, String host, int port, String database) {
        String prefix = dialect == Dialect.MYSQL ? "jdbc:mysql://" : "jdbc:mariadb://";
        String db = database == null ? "" : database;
        return prefix + host + ":" + port + "/" + db
                + "?useUnicode=true&characterEncoding=UTF-8&allowMultiQueries=false";
    }

    private static boolean databaseExists(Connection connection, String database) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?")) {
            statement.setString(1, database);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static int countTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW TABLES")) {
            int count = 0;
            while (rs.next()) {
                count++;
            }
            return count;
        }
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
