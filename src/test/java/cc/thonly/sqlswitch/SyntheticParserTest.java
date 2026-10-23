package cc.thonly.sqlswitch;

import cc.thonly.sqlswitch.model.Column;
import cc.thonly.sqlswitch.model.Dialect;
import cc.thonly.sqlswitch.model.Schema;
import cc.thonly.sqlswitch.model.Table;
import cc.thonly.sqlswitch.model.TargetConfig;
import cc.thonly.sqlswitch.model.Value;
import cc.thonly.sqlswitch.parse.SqlParser;
import cc.thonly.sqlswitch.write.MySqlWriter;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticParserTest {
    private static final String SQLITE_DUMP = """
            PRAGMA foreign_keys = false;

            DROP TABLE IF EXISTS "sqlite_sequence";
            CREATE TABLE "sqlite_sequence" ("name", "seq");
            INSERT INTO "sqlite_sequence" VALUES ('t', 5);

            DROP TABLE IF EXISTS "t";
            CREATE TABLE "t" (
              "id" INTEGER PRIMARY KEY AUTOINCREMENT,
              "name" VARCHAR(30) DEFAULT 共用领域协议,
              "icon" VARCHAR(16) DEFAULT 🎁,
              "flag" INTEGER NOT NULL DEFAULT TRUE,
              "num" BIGINT,
              "data" TEXT,
              "payload" BLOB
            );

            INSERT INTO "t" VALUES (1, 'a', '🎁', 1, '42', 'line1\r\nline2; -- not a comment', X'00FF');
            UPDATE "sqlite_sequence" SET seq = 5 WHERE name = 't';
            """;

    @Test
    void parsesSqliteDefaultsIdentifiersAndAffinity() {
        Schema schema = new SqlParser(Dialect.SQLITE, false).parseText(SQLITE_DUMP);
        Table table = schema.getTable("t");
        assertNotNull(table);
        assertEquals(1, table.rows.size());
        Column name = table.getColumn("name");
        assertTrue(name.hasDefault);
        assertEquals(Value.Kind.STRING, name.defaultValue.kind);
        assertEquals("共用领域协议", name.defaultValue.string);
        Column icon = table.getColumn("icon");
        assertEquals("🎁", icon.defaultValue.string);
        Column flag = table.getColumn("flag");
        assertEquals(Value.Kind.BOOLEAN, flag.defaultValue.kind);

        Value[] row = table.rows.get(0).values;
        assertEquals(Value.Kind.NUMBER, row[table.indexOfColumn("num")].kind);
        assertEquals(42, row[table.indexOfColumn("num")].number.intValue());
        assertEquals(Value.Kind.STRING, row[table.indexOfColumn("data")].kind);
        assertEquals("line1\r\nline2; -- not a comment",
                row[table.indexOfColumn("data")].string);
        assertEquals(Value.Kind.BYTES, row[table.indexOfColumn("payload")].kind);
        assertEquals(5L, schema.sequences.get("t"));
        assertEquals(6, table.autoIncrementValue);
        assertTrue(table.autoIncrementColumn().autoIncrement);
    }

    @Test
    void writesAndReparsesMysqlDialect() {
        Schema schema = new SqlParser(Dialect.SQLITE, false).parseText(SQLITE_DUMP);
        StringWriter out = new StringWriter();
        try {
            new MySqlWriter().write(schema, new TargetConfig(Dialect.MYSQL), out);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        Schema parsed = new SqlParser(Dialect.MYSQL).parseText(out.toString());
        SchemaAssertions.assertSameContent(schema, parsed, false);
        assertTrue(out.toString().contains("DEFAULT '🎁'"));
        assertTrue(out.toString().contains("DEFAULT '共用领域协议'"));
    }

    @Test
    void mysqlDumpWithEscapesParses() {
        String sql = """
                -- MySQL dump 10.13
                CREATE TABLE `m` (
                  `id` int(11) NOT NULL AUTO_INCREMENT,
                  `name` varchar(255) DEFAULT NULL,
                  `flag` tinyint(1) DEFAULT '0',
                  `amount` decimal(10,2) DEFAULT '0.00',
                  `created` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  PRIMARY KEY (`id`),
                  UNIQUE KEY `uq_name` (`name`),
                  KEY `idx_flag` (`flag`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                INSERT INTO `m` VALUES (1,'a\\\\b\\'c\\nline',0,1.50,'2024-01-01 00:00:00');
                """;
        Schema schema = new SqlParser(Dialect.MYSQL).parseText(sql);
        Table table = schema.getTable("m");
        assertNotNull(table);
        assertEquals(1, table.rows.size());
        assertEquals("a\\b'c\nline", table.rows.get(0).values[1].string);
        assertEquals("CURRENT_TIMESTAMP", table.getColumn("created").defaultValue.expression);
        assertEquals("CURRENT_TIMESTAMP", table.getColumn("created").onUpdate);
        assertEquals(2, table.indexes.size());
    }

    @Test
    void foreignKeysChecksPartialIndexesAndRowidAliasesSurvive() {
        String sql = """
                CREATE TABLE parent (
                  id INTEGER PRIMARY KEY,
                  name VARCHAR(20) NOT NULL UNIQUE
                );
                CREATE TABLE child (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  parent_id INTEGER NOT NULL,
                  note TEXT,
                  CONSTRAINT fk_child_parent FOREIGN KEY (parent_id)
                    REFERENCES parent(id) ON DELETE CASCADE ON UPDATE SET NULL,
                  CONSTRAINT chk_note CHECK (length(note) >= 0)
                );
                CREATE UNIQUE INDEX idx_child_note ON child(note) WHERE note IS NOT NULL;
                INSERT INTO parent VALUES (1, 'p');
                INSERT INTO child VALUES (1, 1, 'n');
                """;
        Schema schema = new SqlParser(Dialect.SQLITE, false).parseText(sql);
        Table parent = schema.getTable("parent");
        Table child = schema.getTable("child");
        assertNotNull(parent);
        assertNotNull(child);
        assertTrue(parent.rowidAlias);
        assertTrue(child.rowidAlias);
        assertTrue(child.getColumn("id").autoIncrement);
        assertEquals(1, child.foreignKeys.size());
        assertEquals("CASCADE", child.foreignKeys.get(0).onDelete);
        assertEquals("SET NULL", child.foreignKeys.get(0).onUpdate);
        assertEquals(1, child.checks.size());
        assertEquals("length(note) >= 0", child.checks.get(0).expression);
        assertEquals(1, child.indexes.size());
        assertNotNull(child.indexes.get(0).where);

        StringWriter mysql = new StringWriter();
        try {
            new MySqlWriter().write(schema, new TargetConfig(Dialect.MYSQL), mysql);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertTrue(mysql.toString().contains("AUTO_INCREMENT"));
        assertTrue(mysql.toString().contains("FOREIGN KEY"));
        assertTrue(mysql.toString().contains("CHECK"));
    }
}
