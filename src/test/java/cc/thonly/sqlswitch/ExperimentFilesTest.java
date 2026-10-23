package cc.thonly.sqlswitch;

import cc.thonly.sqlswitch.model.Dialect;
import cc.thonly.sqlswitch.model.Schema;
import cc.thonly.sqlswitch.model.TargetConfig;
import cc.thonly.sqlswitch.parse.SqlParser;
import cc.thonly.sqlswitch.parse.StatementSplitter;
import cc.thonly.sqlswitch.sqlite.SqliteDatabaseReader;
import cc.thonly.sqlswitch.write.MariaDbWriter;
import cc.thonly.sqlswitch.write.MySqlWriter;
import cc.thonly.sqlswitch.write.SqliteWriter;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests against the two experiment files in the project root.
 * They are skipped automatically if those files are absent.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ExperimentFilesTest {
    private Schema database;
    private Schema dump;

    @BeforeAll
    void load() throws Exception {
        Path db = Path.of("wiki.db");
        Path sql = Path.of("main.sql");
        Assumptions.assumeTrue(Files.isRegularFile(db), "wiki.db is not present");
        Assumptions.assumeTrue(Files.isRegularFile(sql), "main.sql is not present");
        Class.forName("org.sqlite.JDBC");
        database = new SqliteDatabaseReader(db).read();
        dump = new SqlParser(Dialect.SQLITE, false).parseFile(sql);
    }

    @Test
    void databaseAndDumpHaveTheSameContent() {
        SchemaAssertions.assertSameContent(database, dump, true);
    }

    @Test
    void mysqlWriterRoundTrips() {
        TargetConfig config = new TargetConfig(Dialect.MYSQL);
        StringWriter out = new StringWriter();
        try {
            new MySqlWriter().write(database, config, out);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        Schema parsed = new SqlParser(Dialect.MYSQL).parseText(out.toString());
        SchemaAssertions.assertSameContent(database, parsed, false);
        SchemaAssertions.assertAutoIncrementValues(database, parsed);
    }

    @Test
    void mariaDbWriterRoundTrips() {
        TargetConfig config = new TargetConfig(Dialect.MARIADB);
        StringWriter out = new StringWriter();
        try {
            new MariaDbWriter().write(database, config, out);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        Schema parsed = new SqlParser(Dialect.MARIADB).parseText(out.toString());
        SchemaAssertions.assertSameContent(database, parsed, false);
        SchemaAssertions.assertAutoIncrementValues(database, parsed);
    }

    @Test
    void sqliteWriterRoundTripsThroughSqlite() throws Exception {
        TargetConfig config = new TargetConfig(Dialect.SQLITE);
        StringWriter out = new StringWriter();
        new SqliteWriter().write(database, config, out);
        Schema roundTripped = executeIntoSqlite(out.toString());
        SchemaAssertions.assertSameContent(database, roundTripped, true);
    }

    private Schema executeIntoSqlite(String sql) throws Exception {
        Path temp = Files.createTempFile("sqlswitch-test-", ".db");
        try {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + temp)) {
                connection.setAutoCommit(true);
                try (Reader reader = new java.io.StringReader(sql)) {
                    StatementSplitter splitter = new StatementSplitter(reader, Dialect.SQLITE,
                            false, 64 * 1024 * 1024);
                    List<StatementSplitter.SqlStatement> statements = splitter.splitAll();
                    for (StatementSplitter.SqlStatement statement : statements) {
                        try (Statement jdbc = connection.createStatement()) {
                            jdbc.execute(statement.text());
                        }
                    }
                }
            }
            assertTrue(Files.size(temp) > 0);
            return new SqliteDatabaseReader(temp).read();
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
