package cc.thonly.sqlswitch.model;

/**
 * Documents where a schema came from.  Used in generated file headers and in
 * the CLI summary.
 */
public final class SourceInfo {
    public Dialect dialect = Dialect.SQLITE;
    public String location = "";
    public boolean databaseFile;
    public String serverVersion;
    public String detectedBy = "";
    public String characterEncoding = "UTF-8";

    public SourceInfo() {
    }

    public SourceInfo(Dialect dialect, String location, boolean databaseFile) {
        this.dialect = dialect;
        this.location = location;
        this.databaseFile = databaseFile;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(dialect.displayName());
        if (databaseFile) {
            sb.append(" 数据库文件");
        } else {
            sb.append(" SQL 导出文件");
        }
        if (location != null && !location.isBlank()) {
            sb.append(": ").append(location);
        }
        if (serverVersion != null && !serverVersion.isBlank()) {
            sb.append(" (server ").append(serverVersion).append(')');
        }
        return sb.toString();
    }
}
