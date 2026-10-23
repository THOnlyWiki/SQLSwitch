package cc.thonly.sqlswitch.write;

/**
 * MariaDB dump writer.  The MySQL/MariaDB syntax is shared; the differences
 * (utf8mb3 vs utf8mb4, JSON, index prefix limits and ROW_FORMAT) are driven
 * by {@link cc.thonly.sqlswitch.model.TargetConfig#isLegacyMariaDb()}.
 */
public final class MariaDbWriter extends MySqlWriter {
    @Override
    protected String dialectName() {
        return "MariaDB";
    }
}
