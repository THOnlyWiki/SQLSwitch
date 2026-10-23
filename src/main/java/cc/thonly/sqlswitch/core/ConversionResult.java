package cc.thonly.sqlswitch.core;

import cc.thonly.sqlswitch.model.Dialect;

import java.nio.file.Path;
import java.util.List;

public record ConversionResult(
        Path output,
        Dialect sourceDialect,
        Dialect targetDialect,
        boolean databaseFile,
        int tables,
        long rows,
        long columns,
        int warnings,
        long bytes,
        long millis,
        List<String> messages
) {
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(sourceDialect.displayName());
        if (databaseFile) {
            sb.append(" database");
        } else {
            sb.append(" dump");
        }
        sb.append(" -> ").append(targetDialect.displayName());
        sb.append(": ").append(tables).append(" tables, ").append(rows).append(" rows, ")
                .append(columns).append(" columns");
        if (warnings > 0) {
            sb.append(", ").append(warnings).append(" warnings");
        }
        if (output != null) {
            sb.append(", ").append(bytes).append(" bytes -> ").append(output);
        }
        sb.append(" [").append(millis).append(" ms]");
        return sb.toString();
    }
}
